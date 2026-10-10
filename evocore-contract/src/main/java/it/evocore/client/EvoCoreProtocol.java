package it.evocore.client;

public final class EvoCoreProtocol {
    public static final int MIN_VERSION = 2;
    public static final int VERSION = 3;
    public static final String HOST_PACKAGE = "it.evocore.host";
    public static final String HOST_SERVICE = "it.evocore.host.EvoCoreService";
    public static final String BIND_PERMISSION = "it.evocore.permission.BIND_CORE";
    public static final String EXTRA_OPEN_TAB = "it.evocore.extra.OPEN_TAB";

    public static final String META_CLIENT_ID = "it.evocore.client.ID";
    public static final String META_NAMESPACE = "it.evocore.client.NAMESPACE";
    public static final String META_CAPABILITIES = "it.evocore.client.CAPABILITIES";

    public static final String KEY_OK = "ok";
    public static final String KEY_ERROR = "error";
    public static final String KEY_ERROR_CODE = "errorCode";
    public static final String KEY_PROTOCOL = "protocolVersion";
    public static final String KEY_PROTOCOL_MIN = "protocolMinVersion";
    public static final String KEY_PACKAGE = "packageName";
    public static final String KEY_CLIENT_ID = "clientId";
    public static final String KEY_NONCE = "nonce";
    public static final String KEY_SESSION = "sessionToken";
    public static final String KEY_NAMESPACE = "namespace";
    public static final String KEY_CAPABILITIES = "capabilities";
    public static final String KEY_REQUESTED_CAPABILITIES = "requestedCapabilities";
    public static final String KEY_CONNECTION_STATE = "connectionState";
    public static final String KEY_CONTEXT_JSON = "contextJson";
    public static final String KEY_QUESTION = "question";
    public static final String KEY_PERSONA = "persona";
    public static final String KEY_ANSWER_JSON = "answerJson";
    public static final String KEY_ENGINE = "engine";
    public static final String KEY_TEXT = "text";
    public static final String KEY_TOPIC = "topic";
    public static final String KEY_KIND = "kind";
    public static final String KEY_STATE = "state";
    public static final String KEY_SENSITIVITY = "sensitivity";
    public static final String KEY_SOURCE = "source";
    public static final String KEY_MEMORY_ID = "memoryId";
    public static final String KEY_ACTION = "action";
    public static final String KEY_MEMORIES = "memories";
    public static final String KEY_STATUS = "status";
    public static final String KEY_GRANT_EPOCH = "grantEpoch";
    public static final String KEY_FEATURES = "features";
    public static final String KEY_MAX_REQUEST_BYTES = "maxRequestBytes";
    public static final String KEY_MAX_EVENT_BYTES = "maxEventBytes";

    public static final int ERROR_UNAUTHORIZED = 10;
    public static final int ERROR_PROTOCOL = 11;
    public static final int ERROR_INVALID_REQUEST = 12;
    public static final int ERROR_BUSY = 13;
    public static final int ERROR_INTERNAL = 14;
    public static final int ERROR_PAIRING_REQUIRED = 15;
    public static final int ERROR_REVOKED = 16;
    public static final int ERROR_CAPABILITY_DENIED = 17;
    public static final int ERROR_IDENTITY = 18;
    public static final int ERROR_CANCELLED = 19;
    public static final int ERROR_REQUEST_ID_REUSE = 20;
    public static final int ERROR_TOOL_DENIED = 21;
    public static final int ERROR_TOOL_RESULT = 22;
    public static final int ERROR_DEADLINE = 23;

    public static final String STATE_DISCONNECTED = "disconnected";
    public static final String STATE_PAIRING_REQUIRED = "pairing_required";
    public static final String STATE_READY = "ready";
    public static final String STATE_REVOKED = "revoked";
    public static final String STATE_INCOMPATIBLE = "incompatible";

    public static final String CAP_INFERENCE_ASK = "inference.ask";
    public static final String CAP_MEMORY_READ = "memory.read";
    public static final String CAP_MEMORY_WRITE = "memory.write";
    public static final String CAP_MEMORY_MANAGE = "memory.manage";
    public static final String CAP_STATUS_READ = "status.read";
    public static final String CAP_TOOLS_PROPOSE = "tools.propose";

    public static final long FEATURE_STREAMING = 1L;
    public static final long FEATURE_CANCEL = 1L << 1;
    public static final long FEATURE_TYPED_TOOLS = 1L << 2;

    private EvoCoreProtocol() {
    }
}
