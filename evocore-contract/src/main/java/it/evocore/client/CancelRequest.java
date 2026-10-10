package it.evocore.client;

import android.os.Parcel;
import android.os.Parcelable;

public final class CancelRequest implements Parcelable {
    public static final int PARCEL_VERSION = 1;
    public final String requestId;
    public final String reason;

    public CancelRequest(String requestId, String reason) {
        this.requestId = requestId == null ? "" : requestId;
        this.reason = reason == null ? "" : reason;
    }

    private CancelRequest(Parcel in) {
        ParcelWire.Header header = ParcelWire.beginRead(in);
        requestId = clean(in.readString());
        reason = clean(in.readString());
        ParcelWire.finishRead(in, header);
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        int start = ParcelWire.beginWrite(out, PARCEL_VERSION);
        out.writeString(requestId);
        out.writeString(reason);
        ParcelWire.finishWrite(out, start);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<CancelRequest> CREATOR = new Creator<>() {
        @Override
        public CancelRequest createFromParcel(Parcel in) {
            return new CancelRequest(in);
        }

        @Override
        public CancelRequest[] newArray(int size) {
            return new CancelRequest[size];
        }
    };

    private static String clean(String value) {
        return value == null ? "" : value;
    }
}
