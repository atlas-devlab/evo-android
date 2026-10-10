package it.evocore.client;

import android.os.Parcel;
import android.os.Parcelable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class ToolDescriptor implements Parcelable {
    public static final int PARCEL_VERSION = 1;
    public static final int MODE_READ = 1;
    public static final int MODE_PROPOSE = 2;
    public static final int MODE_EXECUTE = 3;

    public final String toolId;
    public final int schemaVersion;
    public final int mode;
    public final String description;
    public final String inputSchemaJson;
    public final String outputSchemaJson;
    public final List<String> matchTerms;
    public final boolean requiresConfirmation;
    public final long timeoutMs;
    public final int maxPayloadBytes;

    public ToolDescriptor(String toolId, int schemaVersion, int mode, String description,
                          String inputSchemaJson, String outputSchemaJson, List<String> matchTerms,
                          boolean requiresConfirmation, long timeoutMs, int maxPayloadBytes) {
        this.toolId = clean(toolId).toLowerCase(Locale.ROOT);
        this.schemaVersion = schemaVersion;
        this.mode = mode;
        this.description = clean(description);
        this.inputSchemaJson = clean(inputSchemaJson);
        this.outputSchemaJson = clean(outputSchemaJson);
        this.matchTerms = immutable(matchTerms);
        this.requiresConfirmation = requiresConfirmation;
        this.timeoutMs = timeoutMs;
        this.maxPayloadBytes = maxPayloadBytes;
    }

    private ToolDescriptor(Parcel in) {
        ParcelWire.Header header = ParcelWire.beginRead(in);
        toolId = clean(in.readString()).toLowerCase(Locale.ROOT);
        schemaVersion = in.readInt();
        mode = in.readInt();
        description = clean(in.readString());
        inputSchemaJson = clean(in.readString());
        outputSchemaJson = clean(in.readString());
        ArrayList<String> terms = new ArrayList<>();
        in.readStringList(terms);
        matchTerms = immutable(terms);
        requiresConfirmation = in.readInt() != 0;
        timeoutMs = in.readLong();
        maxPayloadBytes = in.readInt();
        ParcelWire.finishRead(in, header);
    }

    public boolean isValid() {
        int schemaBytes = utf8Size(inputSchemaJson) + utf8Size(outputSchemaJson);
        return toolId.matches("[a-z][a-z0-9_.-]{2,95}")
                && schemaVersion > 0
                && (mode == MODE_READ || mode == MODE_PROPOSE)
                && description.length() <= 512
                && matchTerms.size() <= 16
                && schemaBytes <= 24 * 1024
                && timeoutMs >= 250L && timeoutMs <= 30_000L
                && maxPayloadBytes >= 256 && maxPayloadBytes <= 32 * 1024;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        int start = ParcelWire.beginWrite(out, PARCEL_VERSION);
        out.writeString(toolId);
        out.writeInt(schemaVersion);
        out.writeInt(mode);
        out.writeString(description);
        out.writeString(inputSchemaJson);
        out.writeString(outputSchemaJson);
        out.writeStringList(matchTerms);
        out.writeInt(requiresConfirmation ? 1 : 0);
        out.writeLong(timeoutMs);
        out.writeInt(maxPayloadBytes);
        ParcelWire.finishWrite(out, start);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<ToolDescriptor> CREATOR = new Creator<>() {
        @Override
        public ToolDescriptor createFromParcel(Parcel in) {
            return new ToolDescriptor(in);
        }

        @Override
        public ToolDescriptor[] newArray(int size) {
            return new ToolDescriptor[size];
        }
    };

    private static List<String> immutable(List<String> values) {
        ArrayList<String> output = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                String clean = clean(value).trim().toLowerCase(Locale.ROOT);
                if (!clean.isEmpty() && clean.length() <= 64 && !output.contains(clean)) output.add(clean);
            }
        }
        return Collections.unmodifiableList(output);
    }

    private static int utf8Size(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String clean(String value) {
        return value == null ? "" : value;
    }
}
