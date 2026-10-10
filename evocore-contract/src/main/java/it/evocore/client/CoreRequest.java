package it.evocore.client;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CoreRequest implements Parcelable {
    public static final int PARCEL_VERSION = 1;

    public final int protocolVersion;
    public final String requestId;
    public final String conversationId;
    public final long deadlineElapsedMs;
    public final int maxOutputTokens;
    public final ContextEnvelope context;
    public final String question;
    public final String persona;
    public final List<ToolDescriptor> tools;

    public CoreRequest(int protocolVersion, String requestId, String conversationId,
                       long deadlineElapsedMs, int maxOutputTokens, ContextEnvelope context,
                       String question, String persona, List<ToolDescriptor> tools) {
        this.protocolVersion = protocolVersion;
        this.requestId = clean(requestId);
        this.conversationId = clean(conversationId);
        this.deadlineElapsedMs = deadlineElapsedMs;
        this.maxOutputTokens = maxOutputTokens;
        this.context = context;
        this.question = clean(question);
        this.persona = clean(persona);
        this.tools = immutable(tools);
    }

    private CoreRequest(Parcel in) {
        ParcelWire.Header header = ParcelWire.beginRead(in);
        protocolVersion = in.readInt();
        requestId = clean(in.readString());
        conversationId = clean(in.readString());
        deadlineElapsedMs = in.readLong();
        maxOutputTokens = in.readInt();
        context = in.readParcelable(ContextEnvelope.class.getClassLoader());
        question = clean(in.readString());
        persona = clean(in.readString());
        ArrayList<ToolDescriptor> descriptors = new ArrayList<>();
        in.readTypedList(descriptors, ToolDescriptor.CREATOR);
        tools = immutable(descriptors);
        ParcelWire.finishRead(in, header);
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        int start = ParcelWire.beginWrite(out, PARCEL_VERSION);
        out.writeInt(protocolVersion);
        out.writeString(requestId);
        out.writeString(conversationId);
        out.writeLong(deadlineElapsedMs);
        out.writeInt(maxOutputTokens);
        out.writeParcelable(context, flags);
        out.writeString(question);
        out.writeString(persona);
        out.writeTypedList(tools);
        ParcelWire.finishWrite(out, start);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<CoreRequest> CREATOR = new Creator<>() {
        @Override
        public CoreRequest createFromParcel(Parcel in) {
            return new CoreRequest(in);
        }

        @Override
        public CoreRequest[] newArray(int size) {
            return new CoreRequest[size];
        }
    };

    private static List<ToolDescriptor> immutable(List<ToolDescriptor> values) {
        return Collections.unmodifiableList(values == null
                ? new ArrayList<>() : new ArrayList<>(values));
    }

    private static String clean(String value) {
        return value == null ? "" : value;
    }
}
