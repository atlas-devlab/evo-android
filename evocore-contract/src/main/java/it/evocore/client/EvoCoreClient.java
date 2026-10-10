package it.evocore.client;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class EvoCoreClient implements AutoCloseable {
    public interface Listener {
        void onConnectionChanged(boolean ready, String detail);
    }

    public interface StreamListener {
        void onEvent(CoreEvent event);
    }

    public static final class RequestHandle implements AutoCloseable {
        private final EvoCoreClient owner;
        private final String requestId;
        private final CompletableFuture<CoreEvent> terminal = new CompletableFuture<>();

        private RequestHandle(EvoCoreClient owner, String requestId) {
            this.owner = owner;
            this.requestId = requestId;
        }

        public String requestId() {
            return requestId;
        }

        public CompletableFuture<CoreEvent> terminal() {
            return terminal;
        }

        public boolean cancel() {
            return owner.cancelRequest(requestId, "Richiesta annullata dal client");
        }

        @Override
        public void close() {
            cancel();
        }
    }

    public static final class Result {
        public final boolean ok;
        public final Bundle data;
        public final int errorCode;
        public final String error;

        private Result(boolean ok, Bundle data, int errorCode, String error) {
            this.ok = ok;
            this.data = data == null ? new Bundle() : data;
            this.errorCode = errorCode;
            this.error = error == null ? "" : error;
        }

        public static Result success(Bundle data) {
            return new Result(true, data, 0, "");
        }

        public static Result failure(int code, String error) {
            return new Result(false, new Bundle(), code, error);
        }
    }

    private final Context context;
    private final EvoCoreClientConfig config;
    private final Listener listener;
    private volatile IEvoCoreService remote;
    private volatile String sessionToken = "";
    private volatile String detail = "EvoCore non collegato";
    private volatile EvoCoreConnectionState connectionState = EvoCoreConnectionState.DISCONNECTED;
    private volatile int lastErrorCode;
    private volatile int negotiatedProtocolVersion = EvoCoreProtocol.MIN_VERSION;
    private volatile long grantEpoch;
    private volatile List<String> grantedCapabilities = Collections.emptyList();
    private volatile boolean binding;
    private volatile boolean bound;
    private volatile boolean reconnectAllowed;
    private volatile boolean closed;
    private final ConcurrentHashMap<String, PendingRequest> pendingRequests = new ConcurrentHashMap<>();
    private final ExecutorService toolExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "EvoCoreClientTool");
        thread.setDaemon(true);
        return thread;
    });

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            remote = IEvoCoreService.Stub.asInterface(service);
            binding = false;
            bound = true;
            if (closed || !reconnectAllowed) {
                disconnectInternal("Collegamento EvoCore non piu richiesto");
                return;
            }
            applyHandshake(performHandshake());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            remote = null;
            binding = false;
            bound = false;
            sessionToken = "";
            grantEpoch = 0L;
            grantedCapabilities = Collections.emptyList();
            detail = "EvoCore disconnesso";
            connectionState = EvoCoreConnectionState.DISCONNECTED;
            failPending(EvoCoreProtocol.ERROR_INTERNAL, "Connessione EvoCore interrotta");
            notifyListener();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            onServiceDisconnected(name);
            if (reconnectAllowed && !closed) connect();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            binding = false;
            bound = false;
            remote = null;
            detail = "Servizio EvoCore non disponibile";
            connectionState = EvoCoreConnectionState.ERROR;
            notifyListener();
        }
    };

    public EvoCoreClient(Context context, String namespace, Listener listener) {
        this(context, EvoCoreClientConfig.fromManifest(context, cleanNamespace(namespace)), listener);
    }

    public EvoCoreClient(Context context, EvoCoreClientConfig config, Listener listener) {
        this.context = context.getApplicationContext();
        this.config = config == null ? EvoCoreClientConfig.fromManifest(context) : config;
        this.listener = listener;
    }

    public static EvoCoreClient fromManifest(Context context, Listener listener) {
        return new EvoCoreClient(context, EvoCoreClientConfig.fromManifest(context), listener);
    }

    public boolean connect() {
        if (closed) return false;
        reconnectAllowed = true;
        if (remote != null && !sessionToken.isEmpty()) return true;
        if (!isInstalled(context)) {
            detail = "EvoCore non installato";
            connectionState = EvoCoreConnectionState.NOT_INSTALLED;
            notifyListener();
            return false;
        }
        if (!isTrustedHost(context)) {
            detail = "Firma dell'Host EvoCore non attendibile";
            connectionState = EvoCoreConnectionState.UNTRUSTED_HOST;
            lastErrorCode = EvoCoreProtocol.ERROR_IDENTITY;
            notifyListener();
            return false;
        }
        if (remote != null) {
            applyHandshake(performHandshake());
            return isReady();
        }
        if (binding) return false;
        binding = true;
        connectionState = EvoCoreConnectionState.CONNECTING;
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(EvoCoreProtocol.HOST_PACKAGE, EvoCoreProtocol.HOST_SERVICE));
        try {
            boolean accepted = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!accepted) {
                binding = false;
                detail = "EvoCore ha rifiutato il collegamento";
                connectionState = EvoCoreConnectionState.ERROR;
            } else {
                bound = true;
            }
            return accepted;
        } catch (SecurityException error) {
            binding = false;
            detail = "Firma o permesso EvoCore non valido";
            connectionState = EvoCoreConnectionState.ERROR;
            return false;
        }
    }

    public boolean isReady() {
        return remote != null && !sessionToken.isEmpty();
    }

    public String detail() {
        return detail;
    }

    public EvoCoreConnectionState connectionState() {
        return connectionState;
    }

    public int lastErrorCode() {
        return lastErrorCode;
    }

    public List<String> grantedCapabilities() {
        return grantedCapabilities;
    }

    public int negotiatedProtocolVersion() {
        return negotiatedProtocolVersion;
    }

    public EvoCoreClientConfig config() {
        return config;
    }

    public Result askBlocking(String contextJson, String question, String persona, long timeoutMs) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return Result.failure(EvoCoreProtocol.ERROR_INVALID_REQUEST, "La richiesta AI non puo bloccare la UI");
        }
        if (!awaitReady(Math.min(2_500L, timeoutMs))) {
            return Result.failure(EvoCoreProtocol.ERROR_INTERNAL, detail);
        }
        IEvoCoreService service = remote;
        if (service == null) return Result.failure(EvoCoreProtocol.ERROR_INTERNAL, "EvoCore non collegato");

        Bundle request = authenticatedRequest();
        request.putString(EvoCoreProtocol.KEY_CONTEXT_JSON, bounded(contextJson, 48_000));
        request.putString(EvoCoreProtocol.KEY_QUESTION, bounded(question, 12_000));
        request.putString(EvoCoreProtocol.KEY_PERSONA, bounded(persona, 80));
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Result> output = new AtomicReference<>();
        try {
            service.ask(request, new IEvoCoreCallback.Stub() {
                @Override
                public void onResult(Bundle response) {
                    output.set(Result.success(response));
                    latch.countDown();
                }

                @Override
                public void onError(int code, String message) {
                    output.set(Result.failure(code, message));
                    latch.countDown();
                }

                @Override
                public void onEvent(CoreEvent event) {
                    // The legacy blocking call only observes its final v2 callback.
                }
            });
            if (!latch.await(Math.max(500L, timeoutMs), TimeUnit.MILLISECONDS)) {
                return Result.failure(EvoCoreProtocol.ERROR_BUSY, "EvoCore non ha risposto in tempo");
            }
            Result result = output.get();
            result = result == null
                    ? Result.failure(EvoCoreProtocol.ERROR_INTERNAL, "Risposta EvoCore vuota") : result;
            noteOperationFailure(result);
            return result;
        } catch (RemoteException error) {
            remote = null;
            sessionToken = "";
            return Result.failure(EvoCoreProtocol.ERROR_INTERNAL, "Connessione EvoCore interrotta");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return Result.failure(EvoCoreProtocol.ERROR_INTERNAL, "Richiesta interrotta");
        }
    }

    public Result remember(Bundle memory) {
        return callSync(EvoCoreProtocol.KEY_TEXT, memory, Call.REMEMBER);
    }

    public Result listMemories() {
        return callSync("", new Bundle(), Call.LIST);
    }

    public Result updateMemory(long id, String action, String replacement) {
        Bundle request = new Bundle();
        request.putLong(EvoCoreProtocol.KEY_MEMORY_ID, id);
        request.putString(EvoCoreProtocol.KEY_ACTION, action);
        request.putString(EvoCoreProtocol.KEY_TEXT, replacement == null ? "" : replacement);
        return callSync(EvoCoreProtocol.KEY_ACTION, request, Call.UPDATE);
    }

    public Result status() {
        return callSync("", new Bundle(), Call.STATUS);
    }

    public RequestHandle submit(ContextEnvelope contextEnvelope, String question, String persona,
                                String conversationId, long timeoutMs, ToolRegistry registry,
                                Executor eventExecutor, StreamListener streamListener) {
        return submit(contextEnvelope, question, persona, conversationId, timeoutMs, 192,
                registry, eventExecutor, streamListener);
    }

    public RequestHandle submit(ContextEnvelope contextEnvelope, String question, String persona,
                                String conversationId, long timeoutMs, int maxOutputTokens,
                                ToolRegistry registry, Executor eventExecutor,
                                StreamListener streamListener) {
        String requestId = UUID.randomUUID().toString();
        long deadline = SystemClock.elapsedRealtime() + Math.max(1_000L, Math.min(timeoutMs, 120_000L));
        ToolRegistry safeRegistry = registry == null ? new ToolRegistry() : registry;
        CoreRequest request = new CoreRequest(
                Math.max(EvoCoreProtocol.MIN_VERSION, negotiatedProtocolVersion),
                requestId,
                bounded(conversationId, 96),
                deadline,
                Math.max(32, Math.min(512, maxOutputTokens)),
                contextEnvelope,
                bounded(question, 12_000),
                bounded(persona, 80),
                safeRegistry.descriptors());
        return submit(request, safeRegistry, eventExecutor, streamListener);
    }

    public RequestHandle submit(CoreRequest request, ToolRegistry registry, Executor eventExecutor,
                                StreamListener streamListener) {
        String requestId = request == null || request.requestId.isEmpty()
                ? UUID.randomUUID().toString() : request.requestId;
        RequestHandle handle = new RequestHandle(this, requestId);
        Executor callbackExecutor = eventExecutor == null ? Runnable::run : eventExecutor;
        StreamListener listener = streamListener == null ? event -> { } : streamListener;
        PendingRequest pending = new PendingRequest(handle,
                registry == null ? new ToolRegistry() : registry, callbackExecutor, listener);
        if (pendingRequests.putIfAbsent(requestId, pending) != null) {
            completePending(pending, localEvent(requestId, CoreEvent.ERROR,
                    EvoCoreProtocol.ERROR_REQUEST_ID_REUSE, "requestId gia in uso"));
            return handle;
        }
        if (request == null || request.context == null || request.question.trim().isEmpty()) {
            terminate(requestId, localEvent(requestId, CoreEvent.ERROR,
                    EvoCoreProtocol.ERROR_INVALID_REQUEST, "Richiesta EvoCore incompleta"));
            return handle;
        }
        if (!isReady()) connect();
        if (!isReady()) {
            terminate(requestId, localEvent(requestId, CoreEvent.ERROR,
                    EvoCoreProtocol.ERROR_INTERNAL, detail));
            return handle;
        }
        IEvoCoreService service = remote;
        if (service == null) {
            terminate(requestId, localEvent(requestId, CoreEvent.ERROR,
                    EvoCoreProtocol.ERROR_INTERNAL, "EvoCore non collegato"));
            return handle;
        }
        CoreRequest normalized = new CoreRequest(negotiatedProtocolVersion, requestId,
                request.conversationId, request.deadlineElapsedMs, request.maxOutputTokens,
                request.context, request.question, request.persona, request.tools);
        IEvoCoreCallback callback = streamCallback(requestId);
        try {
            if (negotiatedProtocolVersion >= 3) {
                service.submit(authenticatedRequest(), normalized, callback);
            } else {
                deliver(requestId, new CoreEvent(requestId, 0L, CoreEvent.ACCEPTED,
                        SystemClock.elapsedRealtime(), "", "", null, 0, "", "protocol-v2"));
                Bundle legacy = authenticatedRequest();
                legacy.putString(EvoCoreProtocol.KEY_CONTEXT_JSON, request.context.payloadJson);
                legacy.putString(EvoCoreProtocol.KEY_QUESTION, request.question);
                legacy.putString(EvoCoreProtocol.KEY_PERSONA, request.persona);
                service.ask(legacy, callback);
            }
        } catch (RemoteException error) {
            terminate(requestId, localEvent(requestId, CoreEvent.ERROR,
                    EvoCoreProtocol.ERROR_INTERNAL, "Invio a EvoCore non riuscito"));
        }
        return handle;
    }

    private Result callSync(String required, Bundle values, Call call) {
        if (!awaitReady(2_500L)) return Result.failure(EvoCoreProtocol.ERROR_INTERNAL, detail);
        if (!required.isEmpty() && !values.containsKey(required)) {
            return Result.failure(EvoCoreProtocol.ERROR_INVALID_REQUEST, "Campo richiesto assente");
        }
        Bundle request = authenticatedRequest();
        request.putAll(values);
        try {
            Bundle response;
            if (call == Call.REMEMBER) response = remote.remember(request);
            else if (call == Call.LIST) response = remote.listMemories(request);
            else if (call == Call.UPDATE) response = remote.updateMemory(request);
            else response = remote.getStatus(request);
            if (response != null && response.getBoolean(EvoCoreProtocol.KEY_OK, false)) {
                return Result.success(response);
            }
            Result failure = Result.failure(response == null ? EvoCoreProtocol.ERROR_INTERNAL
                            : response.getInt(EvoCoreProtocol.KEY_ERROR_CODE, EvoCoreProtocol.ERROR_INTERNAL),
                    response == null ? "Risposta EvoCore vuota"
                            : response.getString(EvoCoreProtocol.KEY_ERROR, "Operazione non riuscita"));
            noteOperationFailure(failure);
            return failure;
        } catch (RemoteException error) {
            return Result.failure(EvoCoreProtocol.ERROR_INTERNAL, "Connessione EvoCore interrotta");
        }
    }

    private Result performHandshake() {
        IEvoCoreService service = remote;
        if (service == null) return Result.failure(EvoCoreProtocol.ERROR_INTERNAL, "Servizio assente");
        Bundle request = new Bundle();
        request.putInt(EvoCoreProtocol.KEY_PROTOCOL, EvoCoreProtocol.VERSION);
        request.putInt(EvoCoreProtocol.KEY_PROTOCOL_MIN, EvoCoreProtocol.MIN_VERSION);
        request.putString(EvoCoreProtocol.KEY_PACKAGE, context.getPackageName());
        request.putString(EvoCoreProtocol.KEY_CLIENT_ID, config.clientId);
        request.putString(EvoCoreProtocol.KEY_NAMESPACE, config.namespace);
        request.putStringArrayList(EvoCoreProtocol.KEY_REQUESTED_CAPABILITIES,
                new ArrayList<>(config.requestedCapabilities));
        byte[] nonce = new byte[24];
        new SecureRandom().nextBytes(nonce);
        request.putString(EvoCoreProtocol.KEY_NONCE, Base64.getUrlEncoder().withoutPadding().encodeToString(nonce));
        try {
            Bundle response = service.handshake(request);
            if (response == null || !response.getBoolean(EvoCoreProtocol.KEY_OK, false)) {
                return Result.failure(response == null ? EvoCoreProtocol.ERROR_UNAUTHORIZED
                                : response.getInt(EvoCoreProtocol.KEY_ERROR_CODE, EvoCoreProtocol.ERROR_UNAUTHORIZED),
                        response == null ? "Handshake EvoCore vuoto" : response.getString(EvoCoreProtocol.KEY_ERROR, "Handshake rifiutato"));
            }
            sessionToken = response.getString(EvoCoreProtocol.KEY_SESSION, "");
            return sessionToken.isEmpty()
                    ? Result.failure(EvoCoreProtocol.ERROR_PROTOCOL, "Sessione EvoCore assente")
                    : Result.success(response);
        } catch (RemoteException error) {
            return Result.failure(EvoCoreProtocol.ERROR_INTERNAL, "Handshake EvoCore interrotto");
        }
    }

    private Bundle authenticatedRequest() {
        Bundle request = new Bundle();
        request.putInt(EvoCoreProtocol.KEY_PROTOCOL, negotiatedProtocolVersion);
        request.putString(EvoCoreProtocol.KEY_PACKAGE, context.getPackageName());
        request.putString(EvoCoreProtocol.KEY_CLIENT_ID, config.clientId);
        request.putString(EvoCoreProtocol.KEY_NAMESPACE, config.namespace);
        request.putString(EvoCoreProtocol.KEY_SESSION, sessionToken);
        request.putLong(EvoCoreProtocol.KEY_GRANT_EPOCH, grantEpoch);
        return request;
    }

    private boolean awaitReady(long timeoutMs) {
        if (isReady()) return true;
        connect();
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while (!isReady() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(25L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return isReady();
    }

    public static boolean isInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(EvoCoreProtocol.HOST_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    public static boolean isTrustedHost(Context context) {
        if (!isInstalled(context)) return false;
        return context.getPackageManager().checkSignatures(
                context.getPackageName(), EvoCoreProtocol.HOST_PACKAGE) == PackageManager.SIGNATURE_MATCH;
    }

    public static boolean openHost(Context context) {
        return openHost(context, "core");
    }

    public static boolean openAccessManager(Context context) {
        return openHost(context, "access");
    }

    private static boolean openHost(Context context, String tab) {
        if (!isTrustedHost(context)) return false;
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(EvoCoreProtocol.HOST_PACKAGE);
        if (launch == null) return false;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        launch.putExtra(EvoCoreProtocol.EXTRA_OPEN_TAB, tab);
        context.startActivity(launch);
        return true;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        reconnectAllowed = false;
        disconnectInternal("Client EvoCore chiuso");
        toolExecutor.shutdownNow();
    }

    /**
     * Releases the bound EvoCore service while keeping this client reusable for the next
     * foreground consultation.
     */
    public void disconnect() {
        if (closed) return;
        reconnectAllowed = false;
        disconnectInternal("Client EvoCore in pausa");
    }

    private void disconnectInternal(String pendingMessage) {
        IEvoCoreService service = remote;
        if (service != null && !sessionToken.isEmpty() && negotiatedProtocolVersion >= 3) {
            try {
                service.closeSession(authenticatedRequest());
            } catch (RemoteException ignored) {
            }
        }
        failPending(EvoCoreProtocol.ERROR_CANCELLED, pendingMessage);
        if (bound || binding || remote != null) {
            try {
                context.unbindService(connection);
            } catch (IllegalArgumentException ignored) {
            }
        }
        binding = false;
        bound = false;
        remote = null;
        sessionToken = "";
        grantEpoch = 0L;
        grantedCapabilities = Collections.emptyList();
        connectionState = EvoCoreConnectionState.DISCONNECTED;
    }

    private void notifyListener() {
        if (listener != null) listener.onConnectionChanged(isReady(), detail);
    }

    private void applyHandshake(Result result) {
        lastErrorCode = result.ok ? 0 : result.errorCode;
        if (result.ok) {
            detail = result.data.getString(EvoCoreProtocol.KEY_STATUS, "EvoCore collegato");
            negotiatedProtocolVersion = result.data.getInt(
                    EvoCoreProtocol.KEY_PROTOCOL, EvoCoreProtocol.MIN_VERSION);
            grantEpoch = result.data.getLong(EvoCoreProtocol.KEY_GRANT_EPOCH, 0L);
            ArrayList<String> capabilities = result.data.getStringArrayList(EvoCoreProtocol.KEY_CAPABILITIES);
            grantedCapabilities = capabilities == null
                    ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(capabilities));
            connectionState = EvoCoreConnectionState.READY;
        } else {
            sessionToken = "";
            grantEpoch = 0L;
            grantedCapabilities = Collections.emptyList();
            detail = result.error;
            if (result.errorCode == EvoCoreProtocol.ERROR_PAIRING_REQUIRED) {
                connectionState = EvoCoreConnectionState.PAIRING_REQUIRED;
            } else if (result.errorCode == EvoCoreProtocol.ERROR_REVOKED) {
                connectionState = EvoCoreConnectionState.REVOKED;
            } else if (result.errorCode == EvoCoreProtocol.ERROR_PROTOCOL) {
                connectionState = EvoCoreConnectionState.INCOMPATIBLE;
            } else {
                connectionState = EvoCoreConnectionState.ERROR;
            }
        }
        notifyListener();
    }

    private void noteOperationFailure(Result result) {
        if (result == null || result.ok) return;
        lastErrorCode = result.errorCode;
        if (result.errorCode == EvoCoreProtocol.ERROR_REVOKED) {
            sessionToken = "";
            grantEpoch = 0L;
            grantedCapabilities = Collections.emptyList();
            detail = result.error;
            connectionState = EvoCoreConnectionState.REVOKED;
            notifyListener();
        } else if (result.errorCode == EvoCoreProtocol.ERROR_PAIRING_REQUIRED) {
            sessionToken = "";
            grantEpoch = 0L;
            grantedCapabilities = Collections.emptyList();
            detail = result.error;
            connectionState = EvoCoreConnectionState.PAIRING_REQUIRED;
            notifyListener();
        } else if (result.errorCode == EvoCoreProtocol.ERROR_UNAUTHORIZED) {
            sessionToken = "";
            grantEpoch = 0L;
            grantedCapabilities = Collections.emptyList();
            detail = result.error;
            connectionState = EvoCoreConnectionState.DISCONNECTED;
            notifyListener();
        }
    }

    private static String cleanNamespace(String value) {
        String clean = value == null ? "" : value.trim().toLowerCase();
        return clean.matches("[a-z0-9_.-]{2,48}") ? clean : "default";
    }

    private static String bounded(String value, int max) {
        String clean = value == null ? "" : value;
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    private IEvoCoreCallback streamCallback(String requestId) {
        return new IEvoCoreCallback.Stub() {
            @Override
            public void onResult(Bundle response) {
                String answer = response == null ? ""
                        : response.getString(EvoCoreProtocol.KEY_ANSWER_JSON, "");
                String engine = response == null ? ""
                        : response.getString(EvoCoreProtocol.KEY_ENGINE, "protocol-v2");
                terminate(requestId, new CoreEvent(requestId, Long.MAX_VALUE - 1L,
                        CoreEvent.FINAL, SystemClock.elapsedRealtime(), "", answer,
                        null, 0, "", engine));
            }

            @Override
            public void onError(int code, String message) {
                terminate(requestId, localEvent(requestId, CoreEvent.ERROR, code, message));
            }

            @Override
            public void onEvent(CoreEvent event) {
                if (event == null || !requestId.equals(event.requestId)) return;
                if (event.type == CoreEvent.TOOL_CALL && event.toolCall != null) {
                    deliver(requestId, event);
                    executeTool(requestId, event.toolCall);
                } else if (event.isTerminal()) {
                    terminate(requestId, event);
                } else {
                    deliver(requestId, event);
                }
            }
        };
    }

    private void executeTool(String requestId, ToolCall call) {
        PendingRequest pending = pendingRequests.get(requestId);
        if (pending == null || pending.terminal.get()) return;
        toolExecutor.execute(() -> {
            ToolResult result = pending.registry.execute(call);
            IEvoCoreService service = remote;
            if (service == null) {
                terminate(requestId, localEvent(requestId, CoreEvent.ERROR,
                        EvoCoreProtocol.ERROR_INTERNAL, "Host scomparso durante lo strumento"));
                return;
            }
            try {
                service.submitToolResult(authenticatedRequest(), result);
            } catch (RemoteException error) {
                terminate(requestId, localEvent(requestId, CoreEvent.ERROR,
                        EvoCoreProtocol.ERROR_INTERNAL, "Risultato strumento non consegnato"));
            }
        });
    }

    private boolean cancelRequest(String requestId, String reason) {
        PendingRequest pending = pendingRequests.get(requestId);
        if (pending == null || pending.terminal.get()) return false;
        IEvoCoreService service = remote;
        if (service == null || negotiatedProtocolVersion < 3) {
            terminate(requestId, localEvent(requestId, CoreEvent.CANCELLED,
                    EvoCoreProtocol.ERROR_CANCELLED, reason));
            return true;
        }
        try {
            service.cancel(authenticatedRequest(), new CancelRequest(requestId, reason));
            return true;
        } catch (RemoteException error) {
            terminate(requestId, localEvent(requestId, CoreEvent.ERROR,
                    EvoCoreProtocol.ERROR_INTERNAL, "Annullamento non consegnato"));
            return false;
        }
    }

    private void deliver(String requestId, CoreEvent event) {
        PendingRequest pending = pendingRequests.get(requestId);
        if (pending == null || pending.terminal.get()) return;
        pending.executor.execute(() -> pending.listener.onEvent(event));
    }

    private void terminate(String requestId, CoreEvent event) {
        noteStreamFailure(event);
        PendingRequest pending = pendingRequests.remove(requestId);
        if (pending != null) completePending(pending, event);
    }

    private void noteStreamFailure(CoreEvent event) {
        if (event == null || event.type != CoreEvent.ERROR) return;
        lastErrorCode = event.errorCode;
        if (event.errorCode == EvoCoreProtocol.ERROR_REVOKED) {
            sessionToken = "";
            grantEpoch = 0L;
            grantedCapabilities = Collections.emptyList();
            detail = event.errorMessage.isEmpty() ? "Accesso revocato in EvoCore" : event.errorMessage;
            connectionState = EvoCoreConnectionState.REVOKED;
            notifyListener();
        } else if (event.errorCode == EvoCoreProtocol.ERROR_PAIRING_REQUIRED) {
            sessionToken = "";
            grantEpoch = 0L;
            grantedCapabilities = Collections.emptyList();
            detail = event.errorMessage;
            connectionState = EvoCoreConnectionState.PAIRING_REQUIRED;
            notifyListener();
        } else if (event.errorCode == EvoCoreProtocol.ERROR_UNAUTHORIZED) {
            sessionToken = "";
            grantEpoch = 0L;
            grantedCapabilities = Collections.emptyList();
            detail = event.errorMessage;
            connectionState = EvoCoreConnectionState.DISCONNECTED;
            notifyListener();
        } else if (event.errorCode == EvoCoreProtocol.ERROR_PROTOCOL) {
            detail = event.errorMessage;
            connectionState = EvoCoreConnectionState.INCOMPATIBLE;
            notifyListener();
        }
    }

    private static void completePending(PendingRequest pending, CoreEvent event) {
        if (!pending.terminal.compareAndSet(false, true)) return;
        pending.executor.execute(() -> pending.listener.onEvent(event));
        pending.handle.terminal.complete(event);
    }

    private void failPending(int code, String message) {
        for (String requestId : new ArrayList<>(pendingRequests.keySet())) {
            terminate(requestId, localEvent(requestId,
                    code == EvoCoreProtocol.ERROR_CANCELLED ? CoreEvent.CANCELLED : CoreEvent.ERROR,
                    code, message));
        }
    }

    private static CoreEvent localEvent(String requestId, int type, int code, String message) {
        return new CoreEvent(requestId, Long.MAX_VALUE, type, SystemClock.elapsedRealtime(),
                "", "", null, code, message, "client");
    }

    private static final class PendingRequest {
        final RequestHandle handle;
        final ToolRegistry registry;
        final Executor executor;
        final StreamListener listener;
        final AtomicBoolean terminal = new AtomicBoolean();

        PendingRequest(RequestHandle handle, ToolRegistry registry, Executor executor,
                       StreamListener listener) {
            this.handle = handle;
            this.registry = registry;
            this.executor = executor;
            this.listener = listener;
        }
    }

    private enum Call { REMEMBER, LIST, UPDATE, STATUS }
}
