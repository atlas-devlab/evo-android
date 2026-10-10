package it.evocore.telemetry;

import java.util.regex.Pattern;

public final class TelemetryProtocol {
    public static final String LAB_PACKAGE = "it.evocore.lab";
    public static final String COLLECTOR_CLASS = "it.evocore.lab.TelemetryCollectorService";
    public static final String BIND_PERMISSION = "it.evocore.permission.BIND_TELEMETRY";
    public static final int MAX_PAYLOAD_CHARS = 8_192;
    public static final int MAX_TYPE_CHARS = 64;

    private static final Pattern TYPE = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    private TelemetryProtocol() {
    }

    public static boolean isValidType(String value) {
        return value != null && value.length() <= MAX_TYPE_CHARS && TYPE.matcher(value).matches();
    }

    public static String boundedPayload(String value) {
        String safe = value == null ? "{}" : value;
        return safe.length() <= MAX_PAYLOAD_CHARS
                ? safe : safe.substring(0, MAX_PAYLOAD_CHARS);
    }
}
