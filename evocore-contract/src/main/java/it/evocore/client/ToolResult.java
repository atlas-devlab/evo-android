package it.evocore.client;

import android.os.Parcel;
import android.os.Parcelable;

public final class ToolResult implements Parcelable {
    public static final int PARCEL_VERSION = 1;
    public static final int STATUS_OK = 1;
    public static final int STATUS_DENIED = 2;
    public static final int STATUS_FAILED = 3;
    public static final int STATUS_CANCELLED = 4;
    public static final int STATUS_TIMEOUT = 5;

    public final String requestId;
    public final String callId;
    public final String toolId;
    public final int status;
    public final String resultJson;
    public final int errorCode;
    public final String errorMessage;
    public final boolean sideEffectCommitted;

    public ToolResult(String requestId, String callId, String toolId, int status, String resultJson,
                      int errorCode, String errorMessage, boolean sideEffectCommitted) {
        this.requestId = clean(requestId);
        this.callId = clean(callId);
        this.toolId = clean(toolId);
        this.status = status;
        this.resultJson = clean(resultJson);
        this.errorCode = errorCode;
        this.errorMessage = clean(errorMessage);
        this.sideEffectCommitted = sideEffectCommitted;
    }

    private ToolResult(Parcel in) {
        ParcelWire.Header header = ParcelWire.beginRead(in);
        requestId = clean(in.readString());
        callId = clean(in.readString());
        toolId = clean(in.readString());
        status = in.readInt();
        resultJson = clean(in.readString());
        errorCode = in.readInt();
        errorMessage = clean(in.readString());
        sideEffectCommitted = in.readInt() != 0;
        ParcelWire.finishRead(in, header);
    }

    public static ToolResult success(ToolCall call, String resultJson) {
        return new ToolResult(call.requestId, call.callId, call.toolId, STATUS_OK,
                resultJson, 0, "", false);
    }

    public static ToolResult failure(ToolCall call, int status, String message) {
        return new ToolResult(call == null ? "" : call.requestId,
                call == null ? "" : call.callId, call == null ? "" : call.toolId, status,
                "", EvoCoreProtocol.ERROR_TOOL_RESULT, message, false);
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        int start = ParcelWire.beginWrite(out, PARCEL_VERSION);
        out.writeString(requestId);
        out.writeString(callId);
        out.writeString(toolId);
        out.writeInt(status);
        out.writeString(resultJson);
        out.writeInt(errorCode);
        out.writeString(errorMessage);
        out.writeInt(sideEffectCommitted ? 1 : 0);
        ParcelWire.finishWrite(out, start);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<ToolResult> CREATOR = new Creator<>() {
        @Override
        public ToolResult createFromParcel(Parcel in) {
            return new ToolResult(in);
        }

        @Override
        public ToolResult[] newArray(int size) {
            return new ToolResult[size];
        }
    };

    private static String clean(String value) {
        return value == null ? "" : value;
    }
}
