package it.evocore.host;

import android.app.Service;
import android.content.ComponentCallbacks2;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import it.evocore.client.CancelRequest;
import it.evocore.client.ContextEnvelope;
import it.evocore.client.CoreEvent;
import it.evocore.client.CoreRequest;
import it.evocore.client.EvoCoreProtocol;
import it.evocore.client.IEvoCoreCallback;
import it.evocore.client.IEvoCoreService;
import it.evocore.client.ToolCall;
import it.evocore.client.ToolDescriptor;
import it.evocore.client.ToolResult;
import it.evocore.telemetry.TelemetryRuntime;

public final class EvoCoreService extends Service {
    private static final int MAX_CONTEXT_CHARS = 48_000;
    private static final int MAX_QUESTION_CHARS = 12_000;
    private static final int MAX_PENDING_GLOBAL = 16;
    private static final int MAX_IN_FLIGHT_PER_CLIENT = 3;
    private static final int MAX_REQUEST_BYTES = 128 * 1024;
    private static final int MAX_EVENT_BYTES = 16 * 1024;
    private static final int MAX_TOOLS_PER_REQUEST = 16;
    private static final long MAX_REQUEST_MS = 120_000L;
    private static final long REQUEST_ID_TTL_MS = 10L * 60L * 1000L;
    private static final long MODEL_IDLE_UNLOAD_MS = 60_000L;
    private final SessionRegistry sessions = new SessionRegistry();
    private final ConcurrentHashMap<String, AtomicInteger> clientLoads = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ActiveRequest> activeRequests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> recentRequestIds = new ConcurrentHashMap<>();
    private final CoreToolPlanner toolPlanner = new CoreToolPlanner();
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "EvoCoreDeadlines");
        thread.setDaemon(true);
        return thread;
    });
    private final ThreadPoolExecutor inference = new ThreadPoolExecutor(1, 1, 15_000L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(MAX_PENDING_GLOBAL), runnable -> {
                Thread thread = new Thread(runnable, "EvoCoreInference");
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private CallerVerifier verifier;
    private ClientRegistry clientRegistry;
    private EvoCoreDb db;
    private MemoryPolicyGate memoryPolicy;
    private CoreLlamaEngine engine;
    private final Object modelUnloadLock = new Object();
    private volatile ScheduledFuture<?> modelUnloadFuture;

    @Override
    public void onCreate() {
        super.onCreate();
        inference.allowCoreThreadTimeOut(true);
        TelemetryRuntime.start(this);
        verifier = new CallerVerifier(this);
        clientRegistry = new ClientRegistry(this);
        db = new EvoCoreDb(this);
        memoryPolicy = new MemoryPolicyGate();
        engine = new CoreLlamaEngine(this);
        JSONObject metric = new JSONObject();
        try {
            metric.put("modelAvailable", engine.isAvailable());
            metric.put("engine", engine.status());
        } catch (Exception ignored) {
        }
        TelemetryRuntime.event("ai.service.started", metric);
    }

    private final IEvoCoreService.Stub binder = new IEvoCoreService.Stub() {
        @Override
        public Bundle handshake(Bundle request) {
            Bundle input = request == null ? new Bundle() : request;
            int clientMax = input.getInt(EvoCoreProtocol.KEY_PROTOCOL, -1);
            int clientMin = input.getInt(EvoCoreProtocol.KEY_PROTOCOL_MIN, clientMax);
            if (clientMax < EvoCoreProtocol.MIN_VERSION || clientMin > EvoCoreProtocol.VERSION) {
                Bundle incompatible = failure(EvoCoreProtocol.ERROR_PROTOCOL,
                        "Versione protocollo non compatibile");
                incompatible.putString(EvoCoreProtocol.KEY_CONNECTION_STATE, EvoCoreProtocol.STATE_INCOMPATIBLE);
                incompatible.putInt(EvoCoreProtocol.KEY_PROTOCOL, EvoCoreProtocol.VERSION);
                incompatible.putInt(EvoCoreProtocol.KEY_PROTOCOL_MIN, EvoCoreProtocol.MIN_VERSION);
                return incompatible;
            }
            CallerVerifier.Result caller = verifier.verify(
                    input.getString(EvoCoreProtocol.KEY_PACKAGE, ""),
                    input.getString(EvoCoreProtocol.KEY_CLIENT_ID, ""),
                    input.getString(EvoCoreProtocol.KEY_NAMESPACE, ""),
                    input.getStringArrayList(EvoCoreProtocol.KEY_REQUESTED_CAPABILITIES));
            if (!caller.allowed) return failure(EvoCoreProtocol.ERROR_IDENTITY, caller.error);

            ClientRegistry.ClientRecord access = clientRegistry.observe(caller);
            if (access == null || ClientRegistry.STATE_PENDING.equals(access.state)) {
                Bundle pending = failure(EvoCoreProtocol.ERROR_PAIRING_REQUIRED,
                        "Apri EvoCore > Accessi e approva " + caller.label);
                pending.putString(EvoCoreProtocol.KEY_CONNECTION_STATE, EvoCoreProtocol.STATE_PAIRING_REQUIRED);
                pending.putString(EvoCoreProtocol.KEY_PACKAGE, caller.packageName);
                pending.putString(EvoCoreProtocol.KEY_CLIENT_ID, caller.clientId);
                return pending;
            }
            if (ClientRegistry.STATE_REVOKED.equals(access.state)) {
                Bundle revoked = failure(EvoCoreProtocol.ERROR_REVOKED,
                        "Accesso revocato in EvoCore");
                revoked.putString(EvoCoreProtocol.KEY_CONNECTION_STATE, EvoCoreProtocol.STATE_REVOKED);
                return revoked;
            }
            CallerVerifier.Result authorized = caller.withCapabilities(
                    CapabilityPolicy.intersect(caller.capabilities, access.grantedCapabilities));
            if (authorized.capabilities.isEmpty()) {
                return failure(EvoCoreProtocol.ERROR_CAPABILITY_DENIED,
                        "Nessuna capacita EvoCore autorizzata");
            }
            int selectedProtocol = Math.min(clientMax, EvoCoreProtocol.VERSION);
            SessionRegistry.Session session = sessions.open(authorized,
                    input.getString(EvoCoreProtocol.KEY_NONCE, ""), selectedProtocol,
                    access.grantEpoch);
            if (session == null) return failure(EvoCoreProtocol.ERROR_INVALID_REQUEST,
                    "Nonce handshake non valido o gia usato");
            Bundle output = success();
            output.putString(EvoCoreProtocol.KEY_SESSION, session.token);
            output.putStringArrayList(EvoCoreProtocol.KEY_CAPABILITIES, new ArrayList<>(authorized.capabilities));
            output.putString(EvoCoreProtocol.KEY_CONNECTION_STATE, EvoCoreProtocol.STATE_READY);
            output.putInt(EvoCoreProtocol.KEY_PROTOCOL, selectedProtocol);
            output.putLong(EvoCoreProtocol.KEY_GRANT_EPOCH, access.grantEpoch);
            output.putLong(EvoCoreProtocol.KEY_FEATURES, selectedProtocol >= 3
                    ? EvoCoreProtocol.FEATURE_STREAMING | EvoCoreProtocol.FEATURE_CANCEL
                    | EvoCoreProtocol.FEATURE_TYPED_TOOLS : 0L);
            output.putInt(EvoCoreProtocol.KEY_MAX_REQUEST_BYTES, MAX_REQUEST_BYTES);
            output.putInt(EvoCoreProtocol.KEY_MAX_EVENT_BYTES, MAX_EVENT_BYTES);
            output.putString(EvoCoreProtocol.KEY_STATUS,
                    "EvoCore collegato | protocollo " + selectedProtocol);
            return output;
        }

        @Override
        public void ask(Bundle request, IEvoCoreCallback callback) {
            Bundle input = request == null ? new Bundle() : request;
            SessionRegistry.Session session = authenticate(input);
            if (session == null) {
                AuthFailure auth = authenticationFailure(input);
                callbackError(callback, auth.code, auth.message);
                return;
            }
            if (!session.permits(EvoCoreProtocol.CAP_INFERENCE_ASK)) {
                callbackError(callback, EvoCoreProtocol.ERROR_CAPABILITY_DENIED,
                        "Capacita inference.ask non autorizzata");
                return;
            }
            String contextJson = bounded(input.getString(EvoCoreProtocol.KEY_CONTEXT_JSON, ""), MAX_CONTEXT_CHARS);
            String question = bounded(input.getString(EvoCoreProtocol.KEY_QUESTION, ""), MAX_QUESTION_CHARS);
            String persona = bounded(input.getString(EvoCoreProtocol.KEY_PERSONA, "custodian"), 80);
            if (question.trim().isEmpty()) {
                callbackError(callback, EvoCoreProtocol.ERROR_INVALID_REQUEST, "Domanda vuota");
                return;
            }
            long acceptedAtNanos = SystemClock.elapsedRealtimeNanos();
            AtomicInteger load = clientLoads.computeIfAbsent(session.packageName, ignored -> new AtomicInteger());
            if (load.incrementAndGet() > MAX_IN_FLIGHT_PER_CLIENT) {
                releaseLoad(session.packageName, load);
                callbackError(callback, EvoCoreProtocol.ERROR_BUSY,
                        "Questa app ha gia troppe richieste EvoCore in corso");
                return;
            }
            cancelModelUnload();
            try {
                inference.execute(() -> {
                    long startedAtNanos = SystemClock.elapsedRealtimeNanos();
                    try {
                        if (session.permits(EvoCoreProtocol.CAP_MEMORY_WRITE)) {
                            captureExplicitMemory(session.scope, question);
                        }
                        int memoryLimit = memoryLimitFor(question);
                        String memories = session.permits(EvoCoreProtocol.CAP_MEMORY_READ)
                                ? db.contextForAppAndPersonal(
                                session.scope, question, memoryLimit, memoryLimit) : "";
                        String answer = engine.ask(contextJson, question, memories, persona);
                        Bundle output = success();
                        output.putString(EvoCoreProtocol.KEY_ANSWER_JSON, answer);
                        output.putString(EvoCoreProtocol.KEY_ENGINE, engine.status());
                        output.putBoolean("modelFallback", engine.lastUsedFallback());
                        callback.onResult(output);
                        emitLegacyMetric(session.packageName, contextJson, question, persona,
                                acceptedAtNanos, startedAtNanos, true, 0);
                    } catch (Exception error) {
                        emitLegacyMetric(session.packageName, contextJson, question, persona,
                                acceptedAtNanos, startedAtNanos, false,
                                EvoCoreProtocol.ERROR_INTERNAL);
                        callbackError(callback, EvoCoreProtocol.ERROR_INTERNAL,
                                "EvoCore non ha completato la risposta");
                    } finally {
                        releaseLoad(session.packageName, load);
                        scheduleModelUnloadIfIdle();
                    }
                });
            } catch (RejectedExecutionException full) {
                releaseLoad(session.packageName, load);
                emitLegacyMetric(session.packageName, contextJson, question, persona,
                        acceptedAtNanos, acceptedAtNanos, false, EvoCoreProtocol.ERROR_BUSY);
                callbackError(callback, EvoCoreProtocol.ERROR_BUSY, "Coda EvoCore piena: riprova tra poco");
            }
        }

        @Override
        public Bundle remember(Bundle request) {
            Bundle input = request == null ? new Bundle() : request;
            SessionRegistry.Session session = authenticate(input);
            if (session == null) return authenticationFailure(input).toBundle();
            if (!session.permits(EvoCoreProtocol.CAP_MEMORY_WRITE)) {
                return failure(EvoCoreProtocol.ERROR_CAPABILITY_DENIED, "Memoria in scrittura non autorizzata");
            }
            String text = bounded(input.getString(EvoCoreProtocol.KEY_TEXT, ""), 2_001);
            // A client may propose a memory, but only the EvoCore UI can make it authoritative.
            String source = "client:" + session.packageName;
            String requestedState = "";
            MemoryPolicyGate.Decision decision = memoryPolicy.evaluate(text, source, requestedState,
                    System.currentTimeMillis());
            if (!decision.accepted) return failure(EvoCoreProtocol.ERROR_INVALID_REQUEST, decision.reason);
            try {
                EvoCoreDb.MemoryRecord record = db.add(session.scope, text,
                        input.getString(EvoCoreProtocol.KEY_TOPIC, "generale"), decision.kind,
                        decision.state, source, decision.sensitivity,
                        session.packageName, decision.expiresAt, 0L);
                Bundle output = success();
                output.putLong(EvoCoreProtocol.KEY_MEMORY_ID, record.id);
                output.putString(EvoCoreProtocol.KEY_STATE, record.state);
                output.putString(EvoCoreProtocol.KEY_STATUS, decision.reason);
                return output;
            } catch (Exception error) {
                return failure(EvoCoreProtocol.ERROR_INTERNAL, "Il caveau non ha salvato il ricordo");
            }
        }

        @Override
        public Bundle listMemories(Bundle request) {
            Bundle input = request == null ? new Bundle() : request;
            SessionRegistry.Session session = authenticate(input);
            if (session == null) return authenticationFailure(input).toBundle();
            if (!session.permits(EvoCoreProtocol.CAP_MEMORY_READ)) {
                return failure(EvoCoreProtocol.ERROR_CAPABILITY_DENIED, "Memoria in lettura non autorizzata");
            }
            ArrayList<Bundle> rows = new ArrayList<>();
            for (EvoCoreDb.MemoryRecord memory : db.list(session.scope, true)) rows.add(toBundle(memory));
            Bundle output = success();
            output.putParcelableArrayList(EvoCoreProtocol.KEY_MEMORIES, rows);
            return output;
        }

        @Override
        public Bundle updateMemory(Bundle request) {
            Bundle input = request == null ? new Bundle() : request;
            SessionRegistry.Session session = authenticate(input);
            if (session == null) return authenticationFailure(input).toBundle();
            if (!session.permits(EvoCoreProtocol.CAP_MEMORY_MANAGE)) {
                return failure(EvoCoreProtocol.ERROR_CAPABILITY_DENIED, "Gestione memoria non autorizzata");
            }
            long id = input.getLong(EvoCoreProtocol.KEY_MEMORY_ID, 0L);
            String action = input.getString(EvoCoreProtocol.KEY_ACTION, "").toLowerCase(Locale.ROOT);
            try {
                if ("forget".equals(action)) {
                    return db.forget(session.scope, id) ? success()
                            : failure(EvoCoreProtocol.ERROR_INVALID_REQUEST, "Ricordo non trovato");
                }
                if ("confirm".equals(action) || "correct".equals(action)) {
                    return failure(EvoCoreProtocol.ERROR_CAPABILITY_DENIED,
                            "Solo l'interfaccia EvoCore puo confermare o correggere un ricordo");
                }
                return failure(EvoCoreProtocol.ERROR_INVALID_REQUEST, "Azione memoria non riconosciuta");
            } catch (Exception error) {
                return failure(EvoCoreProtocol.ERROR_INTERNAL, "Aggiornamento memoria non riuscito");
            }
        }

        @Override
        public Bundle getStatus(Bundle request) {
            Bundle input = request == null ? new Bundle() : request;
            SessionRegistry.Session session = authenticate(input);
            if (session == null) return authenticationFailure(input).toBundle();
            if (!session.permits(EvoCoreProtocol.CAP_STATUS_READ)) {
                return failure(EvoCoreProtocol.ERROR_CAPABILITY_DENIED, "Stato EvoCore non autorizzato");
            }
            Bundle output = success();
            output.putString(EvoCoreProtocol.KEY_STATUS, engine.status());
            output.putString("vault", db.vaultStatus());
            output.putInt("memoryCount", db.list(session.scope, true).size());
            output.putBoolean("modelAvailable", engine.isAvailable());
            output.putBoolean("modelLoaded", engine.isModelLoaded());
            output.putLong("modelIdleUnloadMs", MODEL_IDLE_UNLOAD_MS);
            output.putInt(EvoCoreProtocol.KEY_PROTOCOL, EvoCoreProtocol.VERSION);
            return output;
        }

        @Override
        public void submit(Bundle auth, CoreRequest request, IEvoCoreCallback callback) {
            if (callback == null) return;
            Bundle input = auth == null ? new Bundle() : auth;
            SessionRegistry.Session session = authenticate(input);
            if (session == null) {
                AuthFailure failure = authenticationFailure(input);
                callbackError(callback, failure.code, failure.message);
                return;
            }
            if (session.protocolVersion < 3 || request == null || request.protocolVersion != session.protocolVersion) {
                callbackError(callback, EvoCoreProtocol.ERROR_PROTOCOL,
                        "Il flusso tipizzato richiede protocollo EvoCore 3");
                return;
            }
            if (!session.permits(EvoCoreProtocol.CAP_INFERENCE_ASK)) {
                callbackError(callback, EvoCoreProtocol.ERROR_CAPABILITY_DENIED,
                        "Capacita inference.ask non autorizzata");
                return;
            }
            String validationError = validateRequest(session, request);
            if (!validationError.isEmpty()) {
                callbackError(callback, EvoCoreProtocol.ERROR_INVALID_REQUEST, validationError);
                return;
            }
            String key = requestKey(session.packageName, request.requestId);
            String fingerprint = fingerprint(request);
            String previous = recentRequestIds.putIfAbsent(key, fingerprint);
            if (previous != null) {
                callbackError(callback, EvoCoreProtocol.ERROR_REQUEST_ID_REUSE,
                        previous.equals(fingerprint)
                                ? "Richiesta gia ricevuta" : "requestId riutilizzato con dati diversi");
                return;
            }
            AtomicInteger load = clientLoads.computeIfAbsent(session.packageName,
                    ignored -> new AtomicInteger());
            if (load.incrementAndGet() > MAX_IN_FLIGHT_PER_CLIENT) {
                releaseLoad(session.packageName, load);
                recentRequestIds.remove(key, fingerprint);
                callbackError(callback, EvoCoreProtocol.ERROR_BUSY,
                        "Questa app ha gia troppe richieste EvoCore in corso");
                return;
            }
            ActiveRequest active = new ActiveRequest(key, fingerprint, session, request, callback, load);
            if (activeRequests.putIfAbsent(key, active) != null) {
                releaseLoad(session.packageName, load);
                callbackError(callback, EvoCoreProtocol.ERROR_REQUEST_ID_REUSE,
                        "Richiesta gia attiva");
                return;
            }
            cancelModelUnload();
            try {
                active.deathRecipient = () -> abandon(active);
                callback.asBinder().linkToDeath(active.deathRecipient, 0);
            } catch (RemoteException dead) {
                abandon(active);
                return;
            }
            emit(active, CoreEvent.ACCEPTED, "", "", null, 0, "");
            long delay = Math.max(1L, request.deadlineElapsedMs - SystemClock.elapsedRealtime());
            active.deadlineFuture = deadlines.schedule(() -> timeout(active), delay,
                    TimeUnit.MILLISECONDS);
            try {
                active.inferenceFuture = inference.submit(() -> processInitial(active));
            } catch (RejectedExecutionException full) {
                finish(active, CoreEvent.ERROR, "", EvoCoreProtocol.ERROR_BUSY,
                        "Coda EvoCore piena: riprova tra poco");
            }
        }

        @Override
        public void cancel(Bundle auth, CancelRequest request) {
            Bundle input = auth == null ? new Bundle() : auth;
            SessionRegistry.Session session = authenticate(input);
            if (session == null || request == null) {
                finishRevokedCaller(input, request == null ? "" : request.requestId);
                return;
            }
            ActiveRequest active = activeRequests.get(requestKey(session.packageName, request.requestId));
            if (active == null || active.session.uid != session.uid
                    || !active.session.token.equals(session.token)) return;
            cancelActive(active, request.reason);
        }

        @Override
        public void submitToolResult(Bundle auth, ToolResult result) {
            Bundle input = auth == null ? new Bundle() : auth;
            SessionRegistry.Session session = authenticate(input);
            if (session == null || result == null) {
                finishRevokedCaller(input, result == null ? "" : result.requestId);
                return;
            }
            ActiveRequest active = activeRequests.get(requestKey(session.packageName, result.requestId));
            if (active == null || active.session.uid != session.uid
                    || !active.session.token.equals(session.token)) return;
            ToolCall pending;
            synchronized (active) {
                pending = active.pendingTool;
                if (pending == null || !pending.callId.equals(result.callId)
                        || !pending.toolId.equals(result.toolId)) {
                    finish(active, CoreEvent.ERROR, "", EvoCoreProtocol.ERROR_TOOL_RESULT,
                            "Risultato strumento inatteso o non verificabile");
                    return;
                }
                active.pendingTool = null;
            }
            if (SystemClock.elapsedRealtime() > pending.deadlineElapsedMs) {
                finish(active, CoreEvent.ERROR, "", EvoCoreProtocol.ERROR_DEADLINE,
                        "Lo strumento locale ha superato il tempo disponibile");
                return;
            }
            if (result.sideEffectCommitted || utf8Size(result.resultJson) > descriptorLimit(active, pending.toolId)) {
                finish(active, CoreEvent.ERROR, "", EvoCoreProtocol.ERROR_TOOL_RESULT,
                        "Il risultato dello strumento viola i limiti dichiarati");
                return;
            }
            emit(active, CoreEvent.TOOL_RESULT_ACCEPTED, "", "", null, 0, "");
            if (result.status != ToolResult.STATUS_OK) {
                finish(active, CoreEvent.ERROR, "", EvoCoreProtocol.ERROR_TOOL_RESULT,
                        result.errorMessage.isEmpty() ? "Strumento locale non riuscito" : result.errorMessage);
                return;
            }
            finish(active, CoreEvent.FINAL, toolFinal(active, pending, result), 0, "");
        }

        @Override
        public void closeSession(Bundle auth) {
            Bundle input = auth == null ? new Bundle() : auth;
            SessionRegistry.Session session = authenticate(input);
            if (session == null) return;
            for (ActiveRequest active : activeRequests.values()) {
                if (active.session.token.equals(session.token)) cancelActive(active, "Sessione chiusa");
            }
            sessions.close(session.token, Binder.getCallingUid(), session.packageName);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        TelemetryRuntime.event("ai.service.stopped", new JSONObject());
        for (ActiveRequest active : activeRequests.values()) abandon(active);
        deadlines.shutdownNow();
        inference.shutdownNow();
        if (engine != null) engine.unload();
        if (db != null) db.lockVault();
        if (clientRegistry != null) clientRegistry.close();
        TelemetryRuntime.stop();
        super.onDestroy();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
                && activeRequests.isEmpty() && clientLoads.isEmpty()) {
            cancelModelUnload();
            if (engine != null) engine.unload();
        }
    }

    private SessionRegistry.Session authenticate(Bundle request) {
        if (request == null) return null;
        int version = request.getInt(EvoCoreProtocol.KEY_PROTOCOL, -1);
        if (version < EvoCoreProtocol.MIN_VERSION || version > EvoCoreProtocol.VERSION) return null;
        SessionRegistry.Session session = sessions.verify(request.getString(EvoCoreProtocol.KEY_SESSION, ""), Binder.getCallingUid(),
                request.getString(EvoCoreProtocol.KEY_PACKAGE, ""),
                request.getString(EvoCoreProtocol.KEY_NAMESPACE, ""));
        if (session == null) return null;
        if (version != session.protocolVersion) return null;
        ClientRegistry.ClientRecord access = clientRegistry.find(session.packageName);
        if (access == null || !access.approved() || access.grantEpoch != session.grantEpoch
                || !access.grantedCapabilities.containsAll(session.capabilities)) return null;
        if (session.protocolVersion >= 3
                && request.getLong(EvoCoreProtocol.KEY_GRANT_EPOCH, -1L) != session.grantEpoch) return null;
        return session;
    }

    private AuthFailure authenticationFailure(Bundle request) {
        String packageName = request == null ? "" : request.getString(EvoCoreProtocol.KEY_PACKAGE, "");
        ClientRegistry.ClientRecord access = packageName.isEmpty() ? null : clientRegistry.find(packageName);
        if (access != null && ClientRegistry.STATE_REVOKED.equals(access.state)) {
            return new AuthFailure(EvoCoreProtocol.ERROR_REVOKED, "Accesso revocato in EvoCore");
        }
        if (access != null && ClientRegistry.STATE_PENDING.equals(access.state)) {
            return new AuthFailure(EvoCoreProtocol.ERROR_PAIRING_REQUIRED,
                    "Apri EvoCore > Accessi e approva questa app");
        }
        return new AuthFailure(EvoCoreProtocol.ERROR_UNAUTHORIZED, "Sessione EvoCore non valida");
    }

    private void captureExplicitMemory(String scope, String question) {
        String focused = QuestionFocus.current(question);
        MemoryPolicyGate.Decision decision = memoryPolicy.evaluateDialogueCapture(
                focused, System.currentTimeMillis());
        if (!decision.accepted) return;
        try {
            for (EvoCoreDb.MemoryRecord existing : db.list(scope, true)) {
                if ("client-dialogue".equals(existing.source)
                        && focused.equalsIgnoreCase(existing.text)) return;
            }
            db.add(scope, focused, "dialogue", decision.kind, decision.state, "client-dialogue",
                    decision.sensitivity, "client-dialogue-candidate", decision.expiresAt, 0L);
        } catch (Exception ignored) {
        }
    }

    private void processInitial(ActiveRequest active) {
        if (active.terminal.get() || active.cancelled.get()) return;
        if (!stillAuthorized(active)) {
            finish(active, CoreEvent.ERROR, "", EvoCoreProtocol.ERROR_REVOKED,
                    "Accesso revocato durante la richiesta");
            return;
        }
        emit(active, CoreEvent.STARTED, "", "", null, 0, "");
        try {
            if (active.session.permits(EvoCoreProtocol.CAP_MEMORY_WRITE)) {
                captureExplicitMemory(active.session.scope, active.request.question);
            }
            ToolCall toolCall = toolPlanner.select(active.request);
            if (toolCall != null) {
                synchronized (active) {
                    if (active.terminal.get()) return;
                    active.pendingTool = toolCall;
                }
                emit(active, CoreEvent.TOOL_CALL, "", "", toolCall, 0, "");
                return;
            }
            int memoryLimit = memoryLimitFor(active.request.question);
            String memories = active.session.permits(EvoCoreProtocol.CAP_MEMORY_READ)
                    ? db.contextForAppAndPersonal(active.session.scope,
                    active.request.question, memoryLimit, memoryLimit) : "";
            String context = boundedContext(active.request.context);
            String answer = engine.askStreaming(context, active.request.question, memories,
                    active.request.persona, active.request.maxOutputTokens, token -> {
                        if (active.cancelled.get() || active.terminal.get() || !stillAuthorized(active)) {
                            return false;
                        }
                        if (token != null && !token.isEmpty()) {
                            emit(active, CoreEvent.TEXT_DELTA, bounded(token, MAX_EVENT_BYTES / 2),
                                    "", null, 0, "");
                        }
                        return !active.cancelled.get() && !active.terminal.get();
                    });
            if (!stillAuthorized(active)) {
                finish(active, CoreEvent.ERROR, "", EvoCoreProtocol.ERROR_REVOKED,
                        "Accesso revocato durante la risposta");
            } else {
                finish(active, CoreEvent.FINAL, answer, 0, "");
            }
        } catch (CancellationException cancelled) {
            if (!active.terminal.get()) {
                finish(active, CoreEvent.CANCELLED, "", EvoCoreProtocol.ERROR_CANCELLED,
                        "Richiesta annullata");
            }
        } catch (Exception error) {
            finish(active, CoreEvent.ERROR, "", EvoCoreProtocol.ERROR_INTERNAL,
                    "EvoCore non ha completato la risposta");
        }
    }

    private String validateRequest(SessionRegistry.Session session, CoreRequest request) {
        if (!isUuid(request.requestId)) return "requestId non valido";
        if (request.conversationId.length() > 96) return "conversationId troppo lungo";
        if (request.question.trim().isEmpty() || utf8Size(request.question) > MAX_QUESTION_CHARS) {
            return "Domanda vuota o troppo lunga";
        }
        if (request.persona.length() > 80) return "Persona non valida";
        if (request.context == null || !request.context.isValidAt(System.currentTimeMillis())) {
            return "Contesto assente, scaduto o troppo grande";
        }
        try {
            Object payload = new JSONTokener(request.context.payloadJson).nextValue();
            if (!(payload instanceof JSONObject) && !(payload instanceof JSONArray)) {
                return "Il payload del contesto deve essere JSON strutturato";
            }
        } catch (Exception invalidJson) {
            return "Payload del contesto non valido";
        }
        long now = SystemClock.elapsedRealtime();
        if (request.deadlineElapsedMs <= now || request.deadlineElapsedMs - now > MAX_REQUEST_MS) {
            return "Scadenza richiesta non valida";
        }
        if (request.maxOutputTokens < 32 || request.maxOutputTokens > 512) {
            return "Limite output non valido";
        }
        if (request.tools.size() > MAX_TOOLS_PER_REQUEST) return "Troppi strumenti dichiarati";
        if (!request.tools.isEmpty() && !session.permits(EvoCoreProtocol.CAP_TOOLS_PROPOSE)) {
            return "Capacita tools.propose non autorizzata";
        }
        Set<String> ids = new HashSet<>();
        for (ToolDescriptor descriptor : request.tools) {
            if (descriptor == null || !descriptor.isValid() || !ids.add(descriptor.toolId)) {
                return "Catalogo strumenti non valido";
            }
        }
        return requestBytes(request) <= MAX_REQUEST_BYTES ? "" : "Richiesta oltre 128 KiB";
    }

    private void emit(ActiveRequest active, int type, String text, String finalJson,
                      ToolCall toolCall, int errorCode, String errorMessage) {
        if (active.terminal.get()) return;
        if (!stillAuthorized(active)) {
            finish(active, CoreEvent.ERROR, "", EvoCoreProtocol.ERROR_REVOKED,
                    "Accesso revocato durante la richiesta");
            return;
        }
        long nowNanos = SystemClock.elapsedRealtimeNanos();
        if (type == CoreEvent.STARTED && active.startedAtNanos == 0L) {
            active.startedAtNanos = nowNanos;
        }
        if (type == CoreEvent.TEXT_DELTA && active.firstDeltaAtNanos == 0L) {
            active.firstDeltaAtNanos = nowNanos;
        }
        CoreEvent event = new CoreEvent(active.request.requestId,
                active.sequence.getAndIncrement(), type, SystemClock.elapsedRealtime(),
                text, finalJson, toolCall, errorCode, errorMessage, engine.status());
        try {
            active.callback.onEvent(event);
        } catch (Exception dead) {
            abandon(active);
        }
    }

    private void finish(ActiveRequest active, int type, String finalJson, int errorCode,
                        String errorMessage) {
        if (!active.terminal.compareAndSet(false, true)) return;
        if (active.deadlineFuture != null) active.deadlineFuture.cancel(false);
        CoreEvent event = new CoreEvent(active.request.requestId,
                active.sequence.getAndIncrement(), type, SystemClock.elapsedRealtime(),
                "", finalJson, null, errorCode, errorMessage,
                engine.status() + (engine.lastUsedFallback()
                        ? " | fallback-regole" : " | risposta-Gemma"));
        try {
            active.callback.onEvent(event);
        } catch (Exception ignored) {
        } finally {
            emitRequestMetric(active, type, errorCode, finalJson);
            cleanup(active);
        }
    }

    private void cancelActive(ActiveRequest active, String reason) {
        if (active.terminal.get()) return;
        active.cancelled.set(true);
        Future<?> future = active.inferenceFuture;
        if (future != null) future.cancel(true);
        finish(active, CoreEvent.CANCELLED, "", EvoCoreProtocol.ERROR_CANCELLED,
                reason == null || reason.trim().isEmpty() ? "Richiesta annullata" : bounded(reason, 160));
    }

    private void timeout(ActiveRequest active) {
        if (active.terminal.get()) return;
        active.cancelled.set(true);
        Future<?> future = active.inferenceFuture;
        if (future != null) future.cancel(true);
        finish(active, CoreEvent.ERROR, "", EvoCoreProtocol.ERROR_DEADLINE,
                "Tempo della richiesta terminato");
    }

    private void abandon(ActiveRequest active) {
        if (!active.terminal.compareAndSet(false, true)) return;
        active.cancelled.set(true);
        if (active.deadlineFuture != null) active.deadlineFuture.cancel(false);
        if (active.inferenceFuture != null) active.inferenceFuture.cancel(true);
        emitRequestMetric(active, CoreEvent.CANCELLED,
                EvoCoreProtocol.ERROR_CANCELLED, "");
        cleanup(active);
    }

    private void emitLegacyMetric(String packageName, String contextJson, String question,
                                  String persona, long acceptedAtNanos, long startedAtNanos,
                                  boolean success, int errorCode) {
        long finishedAtNanos = SystemClock.elapsedRealtimeNanos();
        JSONObject payload = new JSONObject();
        try {
            payload.put("protocol", "legacy");
            payload.put("client", packageName);
            payload.put("state", success ? "success" : "error");
            payload.put("queueMs", nanosToMs(startedAtNanos - acceptedAtNanos));
            payload.put("firstResponseMs", nanosToMs(finishedAtNanos - acceptedAtNanos));
            payload.put("totalMs", nanosToMs(finishedAtNanos - acceptedAtNanos));
            payload.put("fallback", engine.lastUsedFallback());
            payload.put("fallbackReason", engine.lastFallbackReason());
            payload.put("modelAvailable", engine.isAvailable());
            payload.put("engine", engine.status());
            payload.put("errorCode", errorCode);
            payload.put("contextChars", contextJson.length());
            payload.put("questionChars", question.length());
            payload.put("persona", persona);
        } catch (Exception ignored) {
        }
        TelemetryRuntime.event("ai.request.final", payload);
    }

    private void emitRequestMetric(ActiveRequest active, int type, int errorCode,
                                   String finalJson) {
        long finishedAtNanos = SystemClock.elapsedRealtimeNanos();
        long started = active.startedAtNanos == 0L ? finishedAtNanos : active.startedAtNanos;
        long first = active.firstDeltaAtNanos == 0L ? finishedAtNanos : active.firstDeltaAtNanos;
        JSONObject payload = new JSONObject();
        try {
            payload.put("protocol", active.request.protocolVersion);
            payload.put("client", active.session.packageName);
            payload.put("state", eventState(type));
            payload.put("queueMs", nanosToMs(started - active.acceptedAtNanos));
            payload.put("firstResponseMs", nanosToMs(first - active.acceptedAtNanos));
            payload.put("totalMs", nanosToMs(finishedAtNanos - active.acceptedAtNanos));
            payload.put("fallback", engine.lastUsedFallback());
            payload.put("fallbackReason", engine.lastFallbackReason());
            payload.put("modelAvailable", engine.isAvailable());
            payload.put("engine", engine.status());
            payload.put("errorCode", errorCode);
            payload.put("requestBytes", requestBytes(active.request));
            payload.put("contextChars", active.request.context.payloadJson.length());
            payload.put("questionChars", active.request.question.length());
            payload.put("tools", active.request.tools.size());
            payload.put("finalChars", finalJson == null ? 0 : finalJson.length());
            payload.put("persona", active.request.persona);
        } catch (Exception ignored) {
        }
        TelemetryRuntime.event("ai.request.final", payload);
    }

    private static double nanosToMs(long nanos) {
        return Math.max(0L, nanos) / 1_000_000d;
    }

    private static String eventState(int type) {
        if (type == CoreEvent.FINAL) return "success";
        if (type == CoreEvent.CANCELLED) return "cancelled";
        return "error";
    }

    private void cleanup(ActiveRequest active) {
        activeRequests.remove(active.key, active);
        releaseLoad(active.session.packageName, active.load);
        if (active.deathRecipient != null) {
            active.callback.asBinder().unlinkToDeath(active.deathRecipient, 0);
        }
        deadlines.schedule(() -> recentRequestIds.remove(active.key, active.fingerprint),
                REQUEST_ID_TTL_MS, TimeUnit.MILLISECONDS);
        scheduleModelUnloadIfIdle();
    }

    private void cancelModelUnload() {
        synchronized (modelUnloadLock) {
            if (modelUnloadFuture != null) {
                modelUnloadFuture.cancel(false);
                modelUnloadFuture = null;
            }
        }
    }

    private void scheduleModelUnloadIfIdle() {
        if (!activeRequests.isEmpty() || !clientLoads.isEmpty() || !inference.getQueue().isEmpty()) {
            return;
        }
        synchronized (modelUnloadLock) {
            if (modelUnloadFuture != null) modelUnloadFuture.cancel(false);
            try {
                modelUnloadFuture = deadlines.schedule(() -> {
                    synchronized (modelUnloadLock) {
                        modelUnloadFuture = null;
                    }
                    if (activeRequests.isEmpty() && clientLoads.isEmpty()
                            && inference.getQueue().isEmpty() && engine != null) {
                        engine.unload();
                    }
                }, MODEL_IDLE_UNLOAD_MS, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ignored) {
            }
        }
    }

    private boolean stillAuthorized(ActiveRequest active) {
        ClientRegistry.ClientRecord access = clientRegistry.find(active.session.packageName);
        return access != null && access.approved()
                && access.grantEpoch == active.session.grantEpoch
                && access.grantedCapabilities.containsAll(active.session.capabilities);
    }

    private void finishRevokedCaller(Bundle auth, String requestId) {
        if (auth == null || requestId == null || requestId.isEmpty()) return;
        String packageName = auth.getString(EvoCoreProtocol.KEY_PACKAGE, "");
        ActiveRequest active = activeRequests.get(requestKey(packageName, requestId));
        if (active == null || active.session.uid != Binder.getCallingUid()
                || !active.session.token.equals(auth.getString(EvoCoreProtocol.KEY_SESSION, ""))) return;
        ClientRegistry.ClientRecord access = clientRegistry.find(packageName);
        boolean revoked = access == null || !access.approved()
                || access.grantEpoch != active.session.grantEpoch;
        finish(active, CoreEvent.ERROR, "",
                revoked ? EvoCoreProtocol.ERROR_REVOKED : EvoCoreProtocol.ERROR_UNAUTHORIZED,
                revoked ? "Accesso revocato durante la richiesta" : "Sessione non piu valida");
    }

    private static String requestKey(String packageName, String requestId) {
        return packageName + "#" + requestId;
    }

    private static boolean isUuid(String value) {
        try {
            return value != null && UUID.fromString(value).toString().equals(value.toLowerCase(Locale.ROOT));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static int requestBytes(CoreRequest request) {
        int bytes = utf8Size(request.requestId) + utf8Size(request.conversationId)
                + utf8Size(request.question) + utf8Size(request.persona)
                + utf8Size(request.context.schema) + utf8Size(request.context.screen)
                + utf8Size(request.context.payloadJson);
        for (ToolDescriptor descriptor : request.tools) {
            bytes += utf8Size(descriptor.toolId) + utf8Size(descriptor.description)
                    + utf8Size(descriptor.inputSchemaJson) + utf8Size(descriptor.outputSchemaJson);
            for (String term : descriptor.matchTerms) bytes += utf8Size(term);
        }
        return bytes;
    }

    private static String fingerprint(CoreRequest request) {
        String material = request.protocolVersion + "|" + request.requestId + "|"
                + request.conversationId + "|" + request.context.schema + "|"
                + request.context.payloadJson + "|" + request.question + "|" + request.persona
                + "|" + toolFingerprint(request);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(digest.length * 2);
            for (byte value : digest) output.append(String.format(Locale.ROOT, "%02x", value));
            return output.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(material.hashCode());
        }
    }

    private static String toolFingerprint(CoreRequest request) {
        StringBuilder output = new StringBuilder();
        for (ToolDescriptor descriptor : request.tools) {
            output.append(descriptor.toolId).append('|').append(descriptor.schemaVersion)
                    .append('|').append(descriptor.mode).append('|')
                    .append(descriptor.inputSchemaJson).append('|')
                    .append(descriptor.outputSchemaJson).append('|');
            for (String term : descriptor.matchTerms) output.append(term).append(',');
            output.append(';');
        }
        return output.toString();
    }

    private static int descriptorLimit(ActiveRequest active, String toolId) {
        for (ToolDescriptor descriptor : active.request.tools) {
            if (descriptor.toolId.equals(toolId)) return descriptor.maxPayloadBytes;
        }
        return 0;
    }

    private static String boundedContext(ContextEnvelope context) {
        try {
            JSONObject envelope = new JSONObject();
            envelope.put("trust", "CLIENT_ASSERTED");
            envelope.put("schema", context.schema);
            envelope.put("screen", context.screen);
            envelope.put("capturedAt", context.capturedAtEpochMs);
            envelope.put("expiresAt", context.expiresAtEpochMs);
            envelope.put("sensitivity", context.sensitivity);
            envelope.put("payload", new JSONTokener(context.payloadJson).nextValue());
            return envelope.toString();
        } catch (Exception error) {
            return "{}";
        }
    }

    private static int memoryLimitFor(String question) {
        String lower = question == null ? "" : question.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("ricord") || lower.contains("memoria")
                || lower.contains("chi sono") || lower.contains("cosa sai di me") ? 8 : 2;
    }

    private static String toolFinal(ActiveRequest active, ToolCall call, ToolResult result) {
        try {
            Object parsed = new JSONTokener(result.resultJson).nextValue();
            String message = parsed instanceof JSONObject
                    ? ((JSONObject) parsed).optString("message", "Dato letto e verificato dall'app")
                    : "Dato letto e verificato dall'app";
            JSONObject answer = new JSONObject();
            answer.put("domain", "tool");
            answer.put("stato", "verificato");
            answer.put("messaggioBreve", message);
            answer.put("azioni", new JSONArray());
            answer.put("motivo", "Risultato prodotto dallo strumento locale dichiarato dall'app");
            answer.put("toolId", call.toolId);
            answer.put("mode", call.mode == ToolDescriptor.MODE_READ ? "READ" : "PROPOSE");
            answer.put("result", parsed);
            answer.put("source", active.session.packageName);
            return answer.toString();
        } catch (Exception invalid) {
            JSONObject answer = new JSONObject();
            try {
                answer.put("domain", "tool");
                answer.put("stato", "verificato");
                answer.put("messaggioBreve", "Dato letto e verificato dall'app");
                answer.put("resultText", result.resultJson);
            } catch (Exception ignored) {
            }
            return answer.toString();
        }
    }

    private static int utf8Size(String value) {
        return (value == null ? "" : value).getBytes(StandardCharsets.UTF_8).length;
    }

    private static Bundle toBundle(EvoCoreDb.MemoryRecord memory) {
        Bundle row = new Bundle();
        row.putLong(EvoCoreProtocol.KEY_MEMORY_ID, memory.id);
        row.putString(EvoCoreProtocol.KEY_TEXT, memory.text);
        row.putString(EvoCoreProtocol.KEY_TOPIC, memory.topic);
        row.putString(EvoCoreProtocol.KEY_KIND, memory.kind);
        row.putString(EvoCoreProtocol.KEY_STATE, memory.state);
        row.putString(EvoCoreProtocol.KEY_SOURCE, memory.source);
        row.putString(EvoCoreProtocol.KEY_SENSITIVITY, memory.sensitivity);
        row.putLong("createdAt", memory.createdAt);
        row.putLong("updatedAt", memory.updatedAt);
        row.putLong("expiresAt", memory.expiresAt);
        return row;
    }

    private static Bundle success() {
        Bundle output = new Bundle();
        output.putBoolean(EvoCoreProtocol.KEY_OK, true);
        return output;
    }

    private static Bundle failure(int code, String message) {
        Bundle output = new Bundle();
        output.putBoolean(EvoCoreProtocol.KEY_OK, false);
        output.putInt(EvoCoreProtocol.KEY_ERROR_CODE, code);
        output.putString(EvoCoreProtocol.KEY_ERROR, message);
        return output;
    }

    private static final class ActiveRequest {
        final String key;
        final String fingerprint;
        final SessionRegistry.Session session;
        final CoreRequest request;
        final IEvoCoreCallback callback;
        final AtomicInteger load;
        final AtomicBoolean terminal = new AtomicBoolean();
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicLong sequence = new AtomicLong();
        volatile ToolCall pendingTool;
        volatile Future<?> inferenceFuture;
        volatile Future<?> deadlineFuture;
        volatile IBinder.DeathRecipient deathRecipient;
        final long acceptedAtNanos = SystemClock.elapsedRealtimeNanos();
        volatile long startedAtNanos;
        volatile long firstDeltaAtNanos;

        ActiveRequest(String key, String fingerprint, SessionRegistry.Session session,
                      CoreRequest request, IEvoCoreCallback callback, AtomicInteger load) {
            this.key = key;
            this.fingerprint = fingerprint;
            this.session = session;
            this.request = request;
            this.callback = callback;
            this.load = load;
        }
    }

    private static final class AuthFailure {
        final int code;
        final String message;

        AuthFailure(int code, String message) {
            this.code = code;
            this.message = message;
        }

        Bundle toBundle() {
            return failure(code, message);
        }
    }

    private static void callbackError(IEvoCoreCallback callback, int code, String message) {
        if (callback == null) return;
        try {
            callback.onError(code, message);
        } catch (Exception ignored) {
        }
    }

    private static String bounded(String value, int max) {
        String clean = value == null ? "" : value;
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    private void releaseLoad(String packageName, AtomicInteger load) {
        if (load.decrementAndGet() <= 0) clientLoads.remove(packageName, load);
    }
}
