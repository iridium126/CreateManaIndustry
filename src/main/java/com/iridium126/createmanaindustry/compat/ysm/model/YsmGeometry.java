package com.iridium126.createmanaindustry.compat.ysm.model;

import java.util.HashSet;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable editing values. Coordinates are model pixels; rotations are degrees. */
public final class YsmGeometry {
    // Compatibility values only; parser/model traversal no longer enforces
    // CMI-specific node or hierarchy quotas.
    public static final int MAX_DEPTH = Integer.MAX_VALUE;
    public static final int MAX_NODES = Integer.MAX_VALUE;
    public static final int MAX_TEXT = Integer.MAX_VALUE;
    public static final int MAX_JSON = Integer.MAX_VALUE;

    public record Vector(double x, double y, double z) {
        public static final Vector ZERO = new Vector(0, 0, 0);
        public static final Vector ONE = new Vector(1, 1, 1);
        public Vector { finite(x); finite(y); finite(z); }
    }

    /** Face order: north, south, east, west, up, down. UVs use texture pixels. */
    public record Face(double u, double v, double width, double height, int rotation, boolean visible) {
        public Face {
            finite(u); finite(v); finite(width); finite(height);
            if (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270)
                throw new IllegalArgumentException("UV rotation must be 0, 90, 180 or 270");
        }
    }

    public record Cube(Vector origin, Vector size, Vector pivot, Vector rotation, Vector scale,
            double inflate, boolean visible, List<Face> faces, String extraJson) {
        public Cube {
            Objects.requireNonNull(origin); Objects.requireNonNull(size); Objects.requireNonNull(pivot);
            Objects.requireNonNull(rotation); Objects.requireNonNull(scale); finite(inflate);
            if (size.x < 0 || size.y < 0 || size.z < 0) throw new IllegalArgumentException("Negative cube size");
            if (scale.x <= 0 || scale.y <= 0 || scale.z <= 0) throw new IllegalArgumentException("Non-positive scale");
            faces = List.copyOf(faces);
            if (faces.size() != 6) throw new IllegalArgumentException("A cube must have six face entries");
            json(extraJson);
        }
        public static Cube empty() {
            Face face = new Face(0, 0, 1, 1, 0, true);
            return new Cube(Vector.ZERO, Vector.ONE, Vector.ZERO, Vector.ZERO, Vector.ONE,
                    0, true, List.of(face, face, face, face, face, face), "{}");
        }
    }

    /** Only file roots carry provenance; ordinary bones must have a null root. */
    public record Root(String snapshot, String part, int textureWidth, int textureHeight, String descriptionJson) {
        public Root {
            if (snapshot == null || !snapshot.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid resource snapshot digest");
            text(part);
            if (part.isBlank() || part.startsWith("/") || part.contains("\\") || part.contains(":"))
                throw new IllegalArgumentException("Invalid geometry resource path");
            for (String segment : part.split("/", -1)) {
                if (segment.isEmpty() || segment.equals(".") || segment.equals(".."))
                    throw new IllegalArgumentException("Invalid geometry resource path");
            }
            if (textureWidth < 1 || textureHeight < 1)
                throw new IllegalArgumentException("Invalid texture dimensions");
            json(descriptionJson);
        }
    }

    public record Group(String name, Vector pivot, Vector rotation, Vector scale, boolean visible,
            List<Cube> cubes, List<Group> children, Root root, String extraJson) {
        public Group {
            text(name); Objects.requireNonNull(pivot); Objects.requireNonNull(rotation); Objects.requireNonNull(scale);
            if (scale.x <= 0 || scale.y <= 0 || scale.z <= 0) throw new IllegalArgumentException("Non-positive scale");
            cubes = List.copyOf(cubes); children = List.copyOf(children); json(extraJson);
            if (root != null && (!cubes.isEmpty() || !pivot.equals(Vector.ZERO)
                    || !rotation.equals(Vector.ZERO) || !scale.equals(Vector.ONE) || !visible))
                throw new IllegalArgumentException("File roots cannot have a bone transform or cubes");
            for (Group child : children) {
                if (child.root != null) throw new IllegalArgumentException("Nested geometry file root");
            }
        }
        public static Group empty() {
            return new Group("bone", Vector.ZERO, Vector.ZERO, Vector.ONE, true, List.of(), List.of(), null, "{}");
        }
    }

    /** Validates a complete snapshot replacement, including unique names within each file. */
    public static String validateRoots(List<Group> groups, Set<String> expectedParts) {
        if (groups.isEmpty()) throw new IllegalArgumentException("Missing geometry file roots");
        Set<String> parts = new HashSet<>();
        String snapshot = null;
        for (Group group : groups) {
            Root root = group.root;
            if (root == null) throw new IllegalArgumentException("Expected geometry file root");
            if (snapshot == null) snapshot = root.snapshot;
            if (!snapshot.equals(root.snapshot)) throw new IllegalArgumentException("Mixed model snapshots");
            if (!parts.add(root.part)) throw new IllegalArgumentException("Duplicate geometry file");
            Set<String> names = new HashSet<>();
            var pending = new ArrayDeque<Group>(group.children);
            while (!pending.isEmpty()) {
                Group child = pending.removeLast();
                if (child.root != null) throw new IllegalArgumentException("Nested geometry file root");
                if (child.name.isBlank() || !names.add(child.name))
                    throw new IllegalArgumentException("Missing or duplicate bone name");
                pending.addAll(child.children);
            }
        }
        if (!parts.equals(expectedParts)) throw new IllegalArgumentException("Incomplete geometry file list");
        return snapshot;
    }

    private static void finite(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite geometry value");
    }
    private static void text(String value) {
        Objects.requireNonNull(value);
        if (value.length() > MAX_TEXT || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid geometry text");
    }
    private static void json(String value) {
        Objects.requireNonNull(value);
        if (value.length() > MAX_JSON) throw new IllegalArgumentException("Geometry metadata is too large");
    }
    private YsmGeometry() {}
}
