package com.iridium126.createmanaindustry.compat.ysm.model;

import com.google.gson.*;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads Bedrock geometry without discarding unknown document, bone or cube fields. */
public final class YsmGeometryReader {
    private static final String[] FACES = {"north", "south", "east", "west", "up", "down"};
    private record IndexedCube(int order, Cube cube) {}
    public static Group read(String source, String part, String json) {
        try {
            JsonObject document = YsmJson.resource(json);
            JsonArray geometries = document.getAsJsonArray("minecraft:geometry");
            if (geometries == null || geometries.isEmpty())
                throw new IllegalArgumentException("Expected a geometry in the file");
            JsonObject geometry = geometries.get(0).getAsJsonObject();
            JsonObject description = geometry.getAsJsonObject("description");
            var root = new Root(source, part, integer(description, "texture_width", 64),
                    integer(description, "texture_height", 64), description.toString());
            Map<String, JsonObject> bones = new LinkedHashMap<>();
            Map<String, List<String>> children = new LinkedHashMap<>();
            JsonArray data = geometry.has("bones") ? geometry.getAsJsonArray("bones") : new JsonArray();
            if (data.size() >= YsmGeometry.MAX_NODES) throw new IllegalArgumentException("Too many bones");
            for (JsonElement element : data) {
                JsonObject bone = element.getAsJsonObject(); String name = bone.get("name").getAsString();
                if (name.isBlank() || bones.putIfAbsent(name, bone) != null) throw new IllegalArgumentException("Duplicate or empty bone name");
                String parent = bone.has("parent") ? bone.get("parent").getAsString() : "";
                children.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(name);
            }
            for (String parent : children.keySet()) if (!parent.isEmpty() && !bones.containsKey(parent))
                throw new IllegalArgumentException("Missing parent bone");
            Set<String> visited = new HashSet<>(); int[] nodes = {1};
            List<Group> groups = new ArrayList<>();
            for (String name : children.getOrDefault("", List.of())) groups.add(bone(name, bones, children, visited, 2, nodes));
            if (visited.size() != bones.size()) throw new IllegalArgumentException("Cyclic bone hierarchy");
            geometry.remove("bones"); geometry.remove("description");
            return new Group(part, Vector.ZERO, Vector.ZERO, Vector.ONE, true, List.of(), groups, root, document.toString());
        } catch (IllegalStateException | NullPointerException | ClassCastException failure) {
            throw new IllegalArgumentException("Malformed geometry document", failure);
        }
    }
    private static Group bone(String name, Map<String, JsonObject> bones, Map<String, List<String>> children,
            Set<String> visited, int depth, int[] count) {
        if (depth > YsmGeometry.MAX_DEPTH || ++count[0] > YsmGeometry.MAX_NODES || !visited.add(name))
            throw new IllegalArgumentException("Geometry hierarchy exceeds limits");
        JsonObject data = bones.get(name).deepCopy();
        Vector pivot = vector(data, "pivot", Vector.ZERO), rotation = vector(data, "rotation", Vector.ZERO);
        boolean mirror = bool(data, "mirror", false);
        double inflate = number(data, "inflate", 0);
        boolean visible = bool(data, "createmanaindustry:visible", true);
        List<IndexedCube> ordered = new ArrayList<>();
        if (data.has("cubes")) for (JsonElement element : data.getAsJsonArray("cubes")) {
            if (++count[0] > YsmGeometry.MAX_NODES) throw new IllegalArgumentException("Too many cubes");
            JsonObject item = element.getAsJsonObject();
            ordered.add(new IndexedCube(integer(item, "createmanaindustry:order", ordered.size()), cube(item, mirror, inflate)));
        }
        if (data.has("createmanaindustry:hidden_cubes"))
            for (JsonElement element : data.getAsJsonArray("createmanaindustry:hidden_cubes")) {
                if (++count[0] > YsmGeometry.MAX_NODES) throw new IllegalArgumentException("Too many cubes");
                JsonObject item = element.getAsJsonObject();
                ordered.add(new IndexedCube(integer(item, "createmanaindustry:order", ordered.size()), cube(item, mirror, inflate)));
            }
        ordered.sort(java.util.Comparator.comparingInt(IndexedCube::order));
        List<Cube> cubes = new ArrayList<>(ordered.size());
        int lastOrder = -1;
        for (IndexedCube item : ordered) {
            if (item.order() < 0 || item.order() >= ordered.size() || item.order() == lastOrder)
                throw new IllegalArgumentException("Invalid hidden cube order");
            cubes.add(item.cube()); lastOrder = item.order();
        }
        List<Group> descendants = new ArrayList<>();
        for (String child : children.getOrDefault(name, List.of())) descendants.add(bone(child, bones, children, visited, depth + 1, count));
        for (String key : List.of("name", "parent", "pivot", "rotation", "cubes",
                "createmanaindustry:visible", "createmanaindustry:hidden_cubes")) data.remove(key);
        return new Group(name, pivot, rotation, Vector.ONE, visible, cubes, descendants, null, data.toString());
    }
    private static Cube cube(JsonObject input, boolean boneMirror, double boneInflate) {
        JsonObject data = input.deepCopy();
        boolean visible = bool(data, "createmanaindustry:visible", true);
        Vector origin = vector(data, "origin", Vector.ZERO), size = vector(data, "size", Vector.ZERO);
        Vector pivot = vector(data, "pivot", Vector.ZERO), rotation = vector(data, "rotation", Vector.ZERO);
        boolean mirror = bool(data, "mirror", boneMirror);
        double inflate = number(data, "inflate", boneInflate);
        JsonElement uv = data.get("uv");
        if (uv == null) throw new IllegalArgumentException("Cube has no UV mapping");
        List<Face> faces = new ArrayList<>();
        if (uv.isJsonArray()) {
            double[] offset = array(uv, 2);
            double x = Math.floor(size.x()), y = Math.floor(size.y()), z = Math.floor(size.z());
            faces.add(new Face(offset[0] + z, offset[1] + z, x, y, 0, true));
            faces.add(new Face(offset[0] + 2*z + x, offset[1] + z, x, y, 0, true));
            faces.add(new Face(offset[0], offset[1] + z, z, y, 0, true));
            faces.add(new Face(offset[0] + z + x, offset[1] + z, z, y, 0, true));
            faces.add(new Face(offset[0] + z, offset[1], x, z, 0, true));
            faces.add(new Face(offset[0] + z + x, offset[1] + z, x, -z, 0, true));
            data.remove("uv");
        } else {
            JsonObject mapping = uv.getAsJsonObject();
            for (int i = 0; i < FACES.length; i++) {
                if (!mapping.has(FACES[i])) { faces.add(new Face(0, 0, 0, 0, 0, false)); continue; }
                JsonObject face = mapping.getAsJsonObject(FACES[i]);
                double[] offset = array(face.get("uv"), 2);
                double[] extent = face.has("uv_size") ? array(face.get("uv_size"), 2)
                        : new double[]{i < 2 || i > 3 ? size.x() : size.z(), i < 4 ? size.y() : size.z()};
                int turn = integer(face, "uv_rotation", 0);
                if (turn != 0) throw new IllegalArgumentException("UV quarter turns need verified YSM support");
                faces.add(new Face(offset[0], offset[1], extent[0], extent[1], turn, true));
                face.remove("uv"); face.remove("uv_size");
            }
        }
        if (mirror) {
            java.util.Collections.swap(faces, 2, 3);
            faces.replaceAll(f -> new Face(f.u() + f.width(), f.v(), -f.width(), f.height(), f.rotation(), f.visible()));
            if (data.has("uv")) {
                JsonObject uvData = data.getAsJsonObject("uv");
                JsonElement east = uvData.remove("east"), west = uvData.remove("west");
                if (east != null) uvData.add("west", east); if (west != null) uvData.add("east", west);
            }
        }
        for (String key : List.of("origin", "size", "pivot", "rotation", "inflate", "mirror",
                "createmanaindustry:visible", "createmanaindustry:order")) data.remove(key);
        return new Cube(origin, size, pivot, rotation, Vector.ONE, inflate, visible, faces, data.toString());
    }
    private static double[] array(JsonElement value, int length) {
        JsonArray array = value.getAsJsonArray();
        if (array.size() != length) throw new IllegalArgumentException("Wrong geometry vector length");
        double[] out = new double[length]; for (int i = 0; i < length; i++) out[i] = array.get(i).getAsDouble(); return out;
    }
    private static Vector vector(JsonObject object, String key, Vector fallback) {
        if (!object.has(key)) return fallback;
        double[] values = array(object.get(key), 3); return new Vector(values[0], values[1], values[2]);
    }
    private static double number(JsonObject object, String key, double fallback) { return object.has(key) ? object.get(key).getAsDouble() : fallback; }
    private static boolean bool(JsonObject object, String key, boolean fallback) { return object.has(key) ? object.get(key).getAsBoolean() : fallback; }
    private static int integer(JsonObject object, String key, int fallback) {
        double value = number(object, key, fallback);
        if (!Double.isFinite(value) || value != Math.rint(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Expected geometry integer");
        return (int) value;
    }
    private YsmGeometryReader() {}
}
