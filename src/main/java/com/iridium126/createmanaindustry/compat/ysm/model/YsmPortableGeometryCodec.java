package com.iridium126.createmanaindustry.compat.ysm.model;

import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Cube;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Group;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Root;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/** Self-contained text payloads for YSM geometry iotas. No world-scoped node keys are encoded. */
public final class YsmPortableGeometryCodec {
    private static final int MAGIC = 0x434d4959; // CMIY
    private static final int VERSION = 1;
    private static final int CUBE = 1;
    private static final int GROUP = 2;
    private static final String CUBE_PREFIX = "createmanaindustry:cube_";
    private static final String GROUP_PREFIX = "createmanaindustry:group_";
    private static final int MAX_SECTION_BYTES = 64 * 1024 * 1024;
    private static final int MAX_TEXT_BYTES = 4096;
    private static final int MAX_TOKEN_CHARS = 180 * 1024 * 1024;

    public record Source(YsmResourceArchive archive, String part, int geometryIndex) {
        public Source {
            Objects.requireNonNull(archive, "archive");
            Objects.requireNonNull(part, "part");
            if (part.isBlank() || geometryIndex < 0) throw new IllegalArgumentException("Invalid YSM source context");
        }
    }

    public record CubeData(Cube geometry, Source source) {
        public CubeData { Objects.requireNonNull(geometry, "geometry"); }
    }

    public record GroupData(Group geometry, Source source) {
        public GroupData { Objects.requireNonNull(geometry, "geometry"); }
    }

    public static String encodeCube(CubeData value) {
        validateSource(value.source(), null);
        return CUBE_PREFIX + encode(CUBE, YsmGeometryIO.encodeCube(value.geometry()), value.source());
    }

    public static String encodeGroup(GroupData value) {
        validateSource(value.source(), value.geometry());
        if (value.geometry().root() != null && value.source() == null)
            throw new IllegalArgumentException("A YSM file root requires its source resource archive");
        return GROUP_PREFIX + encode(GROUP, YsmGeometryIO.encodeGroup(value.geometry()), value.source());
    }

    public static CubeData decodeCube(String token) {
        return decode(payload(token, CUBE_PREFIX), CUBE, YsmGeometryIO::decodeCube, CubeData::new);
    }

    public static GroupData decodeGroup(String token) {
        return decode(payload(token, GROUP_PREFIX), GROUP, YsmGeometryIO::decodeGroup, GroupData::new);
    }

    public static String cubePrefix() { return CUBE_PREFIX; }
    public static String groupPrefix() { return GROUP_PREFIX; }

    private static String payload(String token, String prefix) {
        Objects.requireNonNull(token, "token");
        if (!token.startsWith(prefix)) throw new IllegalArgumentException("Portable YSM token prefix mismatch");
        return token.substring(prefix.length());
    }

    private static String encode(int kind, byte[] geometry, Source source) {
        requireSectionSize(geometry.length, "geometry");
        if (source != null) requireSectionSize(source.archive().byteSize(), "resource archive");
        try {
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(new DeflaterOutputStream(compressed))) {
                output.writeInt(MAGIC);
                output.writeByte(VERSION);
                output.writeByte(kind);
                writeBytes(output, geometry);
                output.writeBoolean(source != null);
                if (source != null) {
                    output.writeInt(source.archive().byteSize());
                    source.archive().writeTo(output);
                    writeText(output, source.part());
                    output.writeInt(source.geometryIndex());
                }
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(compressed.toByteArray());
        } catch (IOException failure) {
            throw new IllegalArgumentException("Unable to encode portable YSM geometry", failure);
        }
    }

    private static <T, R> R decode(String token, int expectedKind, GeometryDecoder<T> geometryDecoder,
            PayloadFactory<T, R> factory) {
        Objects.requireNonNull(token, "token");
        try {
            if (token.length() > MAX_TOKEN_CHARS) throw new IllegalArgumentException("Portable YSM token exceeds the size limit");
            byte[] compressed = Base64.getUrlDecoder().decode(token);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(compressed).equals(token))
                throw new IllegalArgumentException("Non-canonical Base64URL payload");
            Inflater inflater = new Inflater();
            try (DataInputStream input = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(compressed), inflater))) {
                if (input.readInt() != MAGIC) throw new IllegalArgumentException("Unknown portable YSM payload");
                if (input.readUnsignedByte() != VERSION) throw new IllegalArgumentException("Unsupported portable YSM payload version");
                if (input.readUnsignedByte() != expectedKind) throw new IllegalArgumentException("Portable YSM payload kind mismatch");
                T geometry = geometryDecoder.decode(readSection(input, "geometry"));
                Source source = null;
                if (readBoolean(input)) {
                    YsmResourceArchive archive = YsmResourceArchive.decode(readSection(input, "resource archive"));
                    source = new Source(archive, readText(input), input.readInt());
                    validateSource(source, geometry instanceof Group group ? group : null);
                }
                if (input.read() != -1 || !inflater.finished() || inflater.getRemaining() != 0)
                    throw new IllegalArgumentException("Truncated or trailing portable YSM payload data");
                if (geometry instanceof Group group && group.root() != null && source == null)
                    throw new IllegalArgumentException("A YSM file root requires its source resource archive");
                return factory.create(geometry, source);
            } finally {
                inflater.end();
            }
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new IllegalArgumentException("Invalid portable YSM payload: " + failure.getMessage(), failure);
        }
    }

    private static void validateSource(Source source, Group group) {
        if (source == null) return;
        YsmModelSnapshot snapshot = YsmModelSnapshot.fromArchive(source.archive());
        Root root = snapshot.roots().stream().map(Group::root)
                .filter(Objects::nonNull)
                .filter(candidate -> candidate.part().equals(source.part())
                        && candidate.geometryIndex() == source.geometryIndex())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("YSM source context does not exist in its resource archive"));
        if (group != null && group.root() != null) {
            Root embedded = group.root();
            if (!embedded.snapshot().equals(snapshot.digest()) || !embedded.part().equals(root.part())
                    || embedded.geometryIndex() != root.geometryIndex())
                throw new IllegalArgumentException("YSM group root does not match its source resource archive");
        }
    }

    private static void writeBytes(DataOutputStream output, byte[] bytes) throws IOException {
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static byte[] readSection(DataInputStream input, String description) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > MAX_SECTION_BYTES)
            throw new EOFException("Invalid or oversized portable YSM " + description + " length");
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException("Truncated portable YSM " + description);
        return bytes;
    }

    private static void writeText(DataOutputStream output, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 1 || bytes.length > MAX_TEXT_BYTES)
            throw new IllegalArgumentException("YSM source part exceeds the size limit");
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readText(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > MAX_TEXT_BYTES) throw new EOFException("Invalid YSM source part length");
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException("Truncated YSM source part");
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static void requireSectionSize(int length, String description) {
        if (length < 1 || length > MAX_SECTION_BYTES)
            throw new IllegalArgumentException("Portable YSM " + description + " exceeds the size limit");
    }

    private static boolean readBoolean(DataInputStream input) throws IOException {
        int value = input.readUnsignedByte();
        if (value > 1) throw new IOException("Invalid boolean in portable YSM payload");
        return value != 0;
    }

    @FunctionalInterface private interface GeometryDecoder<T> { T decode(byte[] bytes); }
    @FunctionalInterface private interface PayloadFactory<T, R> { R create(T geometry, Source source); }

    private YsmPortableGeometryCodec() {}
}
