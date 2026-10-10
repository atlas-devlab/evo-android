package it.evocore.client;

public enum EvoCoreConnectionState {
    NOT_INSTALLED,
    UNTRUSTED_HOST,
    DISCONNECTED,
    CONNECTING,
    PAIRING_REQUIRED,
    READY,
    REVOKED,
    INCOMPATIBLE,
    ERROR
}
