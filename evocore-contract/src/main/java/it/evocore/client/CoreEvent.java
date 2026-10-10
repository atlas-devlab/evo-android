package it.evocore.client;

import android.os.Parcel;
import android.os.Parcelable;

public final class CoreEvent implements Parcelable {
    public static final int PARCEL_VERSION = 1;
    public static final int ACCEPTED = 1;
    public static final int STARTED = 2;
    public static final int TEXT_DELTA = 3;
    public static final int TOOL_CALL = 4;
    public static final int TOOL_RESULT_ACCEPTED = 5;
    public static final int FINAL = 6;
    public static final int ERROR = 7;
    public static final int CANCELLED = 8;

    public final String requestId;
    public final long sequence;
    public final int type;
    public final long emittedElapsedMs;
    public final String textDelta;
    public final String finalJson;
    public final ToolCall toolCall;
    public final int errorCode;
    public final String errorMessage;
    public final String engine;

    public CoreEvent(String requestId, long sequence, int type, long emittedElapsedMs,
                     String textDelta, String finalJson, ToolCall toolCall, int errorCode,
                     String errorMessage, String engine) {
        this.requestId = clean(requestId);
        this.sequence = sequence;
        this.type = type;
        this.emittedElapsedMs = emittedElapsedMs;
        this.textDelta = clean(textDelta);
        this.finalJson = clean(finalJson);
        this.toolCall = toolCall;
        this.errorCode = errorCode;
        this.errorMessage = clean(errorMessage);
        this.engine = clean(engine);
    }

    private CoreEvent(Parcel in) {
        ParcelWire.Header header = ParcelWire.beginRead(in);
        requestId = clean(in.readString());
        sequence = in.readLong();
        type = in.readInt();
        emittedElapsedMs = in.readLong();
        textDelta = clean(in.readString());
        finalJson = clean(in.readString());
        toolCall = in.readParcelable(ToolCall.class.getClassLoader());
        errorCode = in.readInt();
        errorMessage = clean(in.readString());
        engine = clean(in.readString());
        ParcelWire.finishRead(in, header);
    }

    public boolean isTerminal() {
        return type == FINAL || type == ERROR || type == CANCELLED;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        int start = ParcelWire.beginWrite(out, PARCEL_VERSION);
        out.writeString(requestId);
        out.writeLong(sequence);
        out.writeInt(type);
        out.writeLong(emittedElapsedMs);
        out.writeString(textDelta);
        out.writeString(finalJson);
        out.writeParcelable(toolCall, flags);
        out.writeInt(errorCode);
        out.writeString(errorMessage);
        out.writeString(engine);
        ParcelWire.finishWrite(out, start);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<CoreEvent> CREATOR = new Creator<>() {
        @Override
        public CoreEvent createFromParcel(Parcel in) {
            return new CoreEvent(in);
        }

        @Override
        public CoreEvent[] newArray(int size) {
            return new CoreEvent[size];
        }
    };

    private static String clean(String value) {
        return value == null ? "" : value;
    }
}
