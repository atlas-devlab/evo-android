package it.evocore.client;

import android.os.Parcel;
import android.os.Parcelable;

public final class ToolCall implements Parcelable {
    public static final int PARCEL_VERSION = 1;

    public final String requestId;
    public final String callId;
    public final String toolId;
    public final int schemaVersion;
    public final int mode;
    public final String argumentsJson;
    public final String idempotencyKey;
    public final long deadlineElapsedMs;
    public final boolean requiresConfirmation;

    public ToolCall(String requestId, String callId, String toolId, int schemaVersion, int mode,
                    String argumentsJson, String idempotencyKey, long deadlineElapsedMs,
                    boolean requiresConfirmation) {
        this.requestId = clean(requestId);
        this.callId = clean(callId);
        this.toolId = clean(toolId);
        this.schemaVersion = schemaVersion;
        this.mode = mode;
        this.argumentsJson = clean(argumentsJson);
        this.idempotencyKey = clean(idempotencyKey);
        this.deadlineElapsedMs = deadlineElapsedMs;
        this.requiresConfirmation = requiresConfirmation;
    }

    private ToolCall(Parcel in) {
        ParcelWire.Header header = ParcelWire.beginRead(in);
        requestId = clean(in.readString());
        callId = clean(in.readString());
        toolId = clean(in.readString());
        schemaVersion = in.readInt();
        mode = in.readInt();
        argumentsJson = clean(in.readString());
        idempotencyKey = clean(in.readString());
        deadlineElapsedMs = in.readLong();
        requiresConfirmation = in.readInt() != 0;
        ParcelWire.finishRead(in, header);
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        int start = ParcelWire.beginWrite(out, PARCEL_VERSION);
        out.writeString(requestId);
        out.writeString(callId);
        out.writeString(toolId);
        out.writeInt(schemaVersion);
        out.writeInt(mode);
        out.writeString(argumentsJson);
        out.writeString(idempotencyKey);
        out.writeLong(deadlineElapsedMs);
        out.writeInt(requiresConfirmation ? 1 : 0);
        ParcelWire.finishWrite(out, start);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<ToolCall> CREATOR = new Creator<>() {
        @Override
        public ToolCall createFromParcel(Parcel in) {
            return new ToolCall(in);
        }

        @Override
        public ToolCall[] newArray(int size) {
            return new ToolCall[size];
        }
    };

    private static String clean(String value) {
        return value == null ? "" : value;
    }
}
