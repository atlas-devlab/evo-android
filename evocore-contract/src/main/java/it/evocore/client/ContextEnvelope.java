package it.evocore.client;

import android.os.Parcel;
import android.os.Parcelable;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class ContextEnvelope implements Parcelable {
    public static final int PARCEL_VERSION = 1;
    public static final long MAX_TTL_MS = 15L * 60L * 1000L;
    public static final int MAX_PAYLOAD_BYTES = 96 * 1024;

    public final String schema;
    public final String screen;
    public final long capturedAtEpochMs;
    public final long expiresAtEpochMs;
    public final String sensitivity;
    public final String payloadJson;

    public ContextEnvelope(String schema, String screen, long capturedAtEpochMs,
                           long expiresAtEpochMs, String sensitivity, String payloadJson) {
        this.schema = clean(schema);
        this.screen = clean(screen);
        this.capturedAtEpochMs = capturedAtEpochMs;
        this.expiresAtEpochMs = expiresAtEpochMs;
        this.sensitivity = clean(sensitivity).toLowerCase(Locale.ROOT);
        this.payloadJson = payloadJson == null ? "" : payloadJson;
    }

    private ContextEnvelope(Parcel in) {
        ParcelWire.Header header = ParcelWire.beginRead(in);
        schema = clean(in.readString());
        screen = clean(in.readString());
        capturedAtEpochMs = in.readLong();
        expiresAtEpochMs = in.readLong();
        sensitivity = clean(in.readString()).toLowerCase(Locale.ROOT);
        payloadJson = clean(in.readString());
        ParcelWire.finishRead(in, header);
    }

    public boolean isValidAt(long nowEpochMs) {
        return schema.matches("[a-z][a-z0-9_.-]{2,95}")
                && screen.length() <= 96
                && capturedAtEpochMs > 0L
                && expiresAtEpochMs > capturedAtEpochMs
                && expiresAtEpochMs - capturedAtEpochMs <= MAX_TTL_MS
                && nowEpochMs <= expiresAtEpochMs
                && utf8Size(payloadJson) <= MAX_PAYLOAD_BYTES;
    }

    public static ContextEnvelope screen(String schema, String screen, String payloadJson) {
        long now = System.currentTimeMillis();
        return new ContextEnvelope(schema, screen, now, now + 5L * 60L * 1000L,
                "personal", payloadJson);
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        int start = ParcelWire.beginWrite(out, PARCEL_VERSION);
        out.writeString(schema);
        out.writeString(screen);
        out.writeLong(capturedAtEpochMs);
        out.writeLong(expiresAtEpochMs);
        out.writeString(sensitivity);
        out.writeString(payloadJson);
        ParcelWire.finishWrite(out, start);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<ContextEnvelope> CREATOR = new Creator<>() {
        @Override
        public ContextEnvelope createFromParcel(Parcel in) {
            return new ContextEnvelope(in);
        }

        @Override
        public ContextEnvelope[] newArray(int size) {
            return new ContextEnvelope[size];
        }
    };

    private static int utf8Size(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String clean(String value) {
        return value == null ? "" : value;
    }
}
