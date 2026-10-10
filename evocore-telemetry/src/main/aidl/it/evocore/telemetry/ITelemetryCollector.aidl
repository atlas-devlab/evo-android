package it.evocore.telemetry;

interface ITelemetryCollector {
    String getActiveSessionId();
    void publishEvent(String type, long elapsedRealtimeNanos, String payloadJson);
}
