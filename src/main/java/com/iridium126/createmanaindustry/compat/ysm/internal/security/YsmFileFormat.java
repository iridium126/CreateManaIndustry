package com.iridium126.createmanaindustry.compat.ysm.internal.security;

import java.nio.charset.StandardCharsets;

/** Detects the outer YSGP container version used by the reference YSMParser. */
public final class YsmFileFormat {
    public record Header(int cryptoVersion, int resourceFormat, int payloadOffset) {}

    public static int cryptoVersion(byte[] file) {
        if (file.length < 8) throw new IllegalArgumentException("Invalid YSM file header");
        if (file.length >= 7 && file[0] == (byte) 0xEF && file[1] == (byte) 0xBB && file[2] == (byte) 0xBF
                && asciiEquals(file, 3, "YSGP")) return 3;
        if (!asciiEquals(file, 0, "YSGP")) throw new IllegalArgumentException("Unsupported YSM signature");
        long version = uint32be(file, 4);
        if (version != 1 && version != 2) throw new IllegalArgumentException("Unsupported YSM crypto version: " + version);
        return (int) version;
    }

    public static Header v3(byte[] file) {
        if (cryptoVersion(file) != 3) throw new IllegalArgumentException("Expected a crypto version 3 YSM file");
        int nul = 0;
        while (nul < file.length && file[nul] != 0) nul++;
        int tailOffset = file.length - 64;
        if (nul + 5 > tailOffset) throw new IllegalArgumentException("Invalid YSM header boundary");
        int format = extractFormat(file, nul);
        int cryptoOffset = nul + 1;
        if (uint32le(file, cryptoOffset) != 3) throw new IllegalArgumentException("Invalid YSM crypto version field");
        return new Header(3, format, cryptoOffset + 4);
    }

    private static int extractFormat(byte[] file, int headerEnd) {
        byte[] label = "<format>".getBytes(StandardCharsets.US_ASCII);
        int start = -1;
        for (int i = 0; i <= headerEnd - label.length; i++) {
            boolean match = true;
            for (int j = 0; j < label.length; j++) if (file[i + j] != label[j]) { match = false; break; }
            if (match) { start = i + label.length; break; }
        }
        if (start < 0) throw new IllegalArgumentException("YSM header is missing <format>");
        while (start < headerEnd && (file[start] == ' ' || file[start] == '\t')) start++;
        int end = start;
        while (end < headerEnd && file[end] >= '0' && file[end] <= '9') end++;
        if (end == start) throw new IllegalArgumentException("Invalid YSM <format> value");
        try {
            int format = Integer.parseInt(new String(file, start, end - start, StandardCharsets.US_ASCII));
            if (format < 1) throw new IllegalArgumentException("Unsupported YSM resource format: " + format);
            return format;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Invalid YSM <format> value", failure);
        }
    }

    static long uint32be(byte[] data, int offset) {
        return ((long) Byte.toUnsignedInt(data[offset]) << 24)
                | ((long) Byte.toUnsignedInt(data[offset + 1]) << 16)
                | ((long) Byte.toUnsignedInt(data[offset + 2]) << 8)
                | Byte.toUnsignedInt(data[offset + 3]);
    }

    private static long uint32le(byte[] data, int offset) {
        return Byte.toUnsignedInt(data[offset])
                | ((long) Byte.toUnsignedInt(data[offset + 1]) << 8)
                | ((long) Byte.toUnsignedInt(data[offset + 2]) << 16)
                | ((long) Byte.toUnsignedInt(data[offset + 3]) << 24);
    }

    private static boolean asciiEquals(byte[] data, int offset, String value) {
        if (offset < 0 || data.length - offset < value.length()) return false;
        for (int i = 0; i < value.length(); i++) if (data[offset + i] != (byte) value.charAt(i)) return false;
        return true;
    }

    private YsmFileFormat() {}
}
