package com.iridium126.createmanaindustry.compat.ysm.model;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.*;

/** Versioned, bounded representation shared by persistence and network codecs. */
public final class YsmGeometryIO {
    // Persistence/network limit for one Hexcasting iota; it is not a YSM parser quota.
    public static final int MAX_BYTES = 1024 * 1024;
    private static final int VERSION = 1;

    public static byte[] encodeCube(Cube cube) { return encode(out -> writeCube(out, cube)); }
    public static byte[] encodeGroup(Group group) { return encode(out -> writeGroup(out, group)); }
    public static Cube decodeCube(byte[] bytes) { return decode(bytes, in -> readCube(in)); }
    public static Group decodeGroup(byte[] bytes) { return decode(bytes, YsmGeometryIO::readGroup); }

    /** Estimates cache weight without imposing the iota codec's per-value byte cap. */
    public static long estimateGroupSize(Group root) {
        long size = 0;
        var pending = new ArrayDeque<Group>();
        pending.push(root);
        while (!pending.isEmpty()) {
            Group group = pending.pop();
            size += textWeight(group.name()) + 3L * 24 + 2 + textWeight(group.extraJson()) + 8;
            if (group.root() != null) {
                Root file = group.root();
                size += textWeight(file.snapshot()) + textWeight(file.part()) + 8 + textWeight(file.descriptionJson());
            }
            for (Cube cube : group.cubes())
                size += 5L * 24 + 8 + 1 + 6L * 37 + textWeight(cube.extraJson());
            for (Group child : group.children()) pending.push(child);
        }
        return size;
    }
    private static long textWeight(String value) { return 4L + 3L * value.length(); }

    private static byte[] encode(Writer writer) {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(new FilterOutputStream(bytes) {
            private long count;
            @Override public void write(int value) throws IOException {
                if (++count > MAX_BYTES) throw new IOException("Geometry exceeds byte limit");
                out.write(value);
            }
            @Override public void write(byte[] data, int offset, int length) throws IOException {
                if (length > MAX_BYTES - count) throw new IOException("Geometry exceeds byte limit");
                count += length;
                out.write(data, offset, length);
            }
        })) {
            out.writeByte(VERSION);
            writer.write(out);
            return bytes.toByteArray();
        } catch (IOException failure) { throw new IllegalArgumentException(failure.getMessage(), failure); }
    }

    private static <T> T decode(byte[] bytes, Reader<T> reader) {
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Geometry exceeds byte limit");
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readUnsignedByte() != VERSION) throw new IOException("Unsupported geometry encoding version");
            T value = reader.read(in);
            if (in.available() != 0) throw new IOException("Trailing geometry data");
            return value;
        } catch (IOException failure) { throw new IllegalArgumentException("Invalid geometry data: " + failure.getMessage(), failure); }
    }

    private static void vector(DataOutputStream out, Vector v) throws IOException {
        out.writeDouble(v.x()); out.writeDouble(v.y()); out.writeDouble(v.z());
    }
    private static Vector vector(DataInputStream in) throws IOException {
        return new Vector(in.readDouble(), in.readDouble(), in.readDouble());
    }
    private static void text(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length); out.write(bytes);
    }
    private static String text(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_BYTES || length > in.available()) throw new IOException("Invalid text length");
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(in.readNBytes(length))).toString();
    }
    private static void writeCube(DataOutputStream out, Cube cube) throws IOException {
        vector(out, cube.origin()); vector(out, cube.size()); vector(out, cube.pivot());
        vector(out, cube.rotation()); vector(out, cube.scale());
        out.writeDouble(cube.inflate()); out.writeBoolean(cube.visible());
        for (Face face : cube.faces()) {
            out.writeDouble(face.u()); out.writeDouble(face.v()); out.writeDouble(face.width()); out.writeDouble(face.height());
            out.writeInt(face.rotation()); out.writeBoolean(face.visible());
        }
        text(out, cube.extraJson());
    }
    private static Cube readCube(DataInputStream in) throws IOException {
        Vector origin = vector(in), size = vector(in), pivot = vector(in), rotation = vector(in), scale = vector(in);
        double inflate = in.readDouble(); boolean visible = bool(in);
        List<Face> faces = new ArrayList<>(6);
        for (int i = 0; i < 6; i++) faces.add(new Face(in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(), in.readInt(), bool(in)));
        return new Cube(origin, size, pivot, rotation, scale, inflate, visible, faces, text(in));
    }
    private static boolean bool(DataInputStream in) throws IOException {
        int value = in.readUnsignedByte();
        if (value > 1) throw new IOException("Invalid boolean");
        return value != 0;
    }
    private static void writeGroup(DataOutputStream out, Group group) throws IOException {
        Deque<Group> pending = new ArrayDeque<>();
        pending.push(group);
        while (!pending.isEmpty()) {
            Group current = pending.pop();
            text(out, current.name()); vector(out, current.pivot()); vector(out, current.rotation()); vector(out, current.scale());
            out.writeBoolean(current.visible()); out.writeBoolean(current.root() != null);
            if (current.root() != null) {
                Root root = current.root(); text(out, root.snapshot()); text(out, root.part());
                out.writeInt(root.textureWidth()); out.writeInt(root.textureHeight()); text(out, root.descriptionJson());
            }
            text(out, current.extraJson()); out.writeInt(current.cubes().size());
            for (Cube cube : current.cubes()) writeCube(out, cube);
            out.writeInt(current.children().size());
            for (int i = current.children().size() - 1; i >= 0; i--) pending.push(current.children().get(i));
        }
    }

    private static final class GroupFrame {
        private final String name, extra;
        private final Vector pivot, rotation, scale;
        private final boolean visible;
        private final Root root;
        private final List<Cube> cubes;
        private final int childCount;
        private final List<Group> children = new ArrayList<>();
        private GroupFrame(String name, Vector pivot, Vector rotation, Vector scale, boolean visible, Root root,
                String extra, List<Cube> cubes, int childCount) {
            this.name = name; this.pivot = pivot; this.rotation = rotation; this.scale = scale;
            this.visible = visible; this.root = root; this.extra = extra; this.cubes = cubes; this.childCount = childCount;
        }
        private Group build() { return new Group(name, pivot, rotation, scale, visible, cubes, children, root, extra); }
    }

    private static GroupFrame readGroupFrame(DataInputStream in) throws IOException {
        String name = text(in);
        Vector pivot = vector(in), rotation = vector(in), scale = vector(in);
        boolean visible = bool(in);
        Root root = bool(in) ? new Root(text(in), text(in), in.readInt(), in.readInt(), text(in)) : null;
        String extra = text(in);
        int cubeCount = count(in);
        List<Cube> cubes = new ArrayList<>(Math.min(cubeCount, 1024));
        for (int i = 0; i < cubeCount; i++) cubes.add(readCube(in));
        int childCount = count(in);
        return new GroupFrame(name, pivot, rotation, scale, visible, root, extra, cubes, childCount);
    }

    private static Group readGroup(DataInputStream in) throws IOException {
        Deque<GroupFrame> pending = new ArrayDeque<>();
        pending.push(readGroupFrame(in));
        while (true) {
            GroupFrame frame = pending.peek();
            if (frame.children.size() < frame.childCount) {
                pending.push(readGroupFrame(in));
                continue;
            }
            Group completed = frame.build();
            pending.pop();
            if (pending.isEmpty()) return completed;
            pending.peek().children.add(completed);
        }
    }
    private static int count(DataInputStream in) throws IOException {
        int count = in.readInt();
        // Every serialized list element consumes at least one byte. This is an
        // input-integrity check, not a model node quota.
        if (count < 0 || count > in.available()) throw new IOException("Invalid geometry list length");
        return count;
    }
    private interface Writer { void write(DataOutputStream out) throws IOException; }
    private interface Reader<T> { T read(DataInputStream in) throws IOException; }
    private YsmGeometryIO() {}
}
