package it.evocore.client;

import android.os.BadParcelableException;
import android.os.Parcel;

final class ParcelWire {
    static final class Header {
        final int version;
        final int end;

        Header(int version, int end) {
            this.version = version;
            this.end = end;
        }
    }

    static int beginWrite(Parcel out, int version) {
        int start = out.dataPosition();
        out.writeInt(version);
        out.writeInt(0);
        return start;
    }

    static void finishWrite(Parcel out, int start) {
        int end = out.dataPosition();
        out.setDataPosition(start + Integer.BYTES);
        out.writeInt(end - start);
        out.setDataPosition(end);
    }

    static Header beginRead(Parcel in) {
        int start = in.dataPosition();
        int version = in.readInt();
        int size = in.readInt();
        if (size < Integer.BYTES * 2 || start > Integer.MAX_VALUE - size) {
            throw new BadParcelableException("Invalid EvoCore parcel size");
        }
        int end = start + size;
        if (end > in.dataSize()) throw new BadParcelableException("Truncated EvoCore parcel");
        return new Header(version, end);
    }

    static void finishRead(Parcel in, Header header) {
        in.setDataPosition(header.end);
    }

    private ParcelWire() {
    }
}
