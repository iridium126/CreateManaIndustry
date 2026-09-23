package com.iridium126.createmanaindustry.compat.ysm.model;

import java.security.MessageDigest;
import java.util.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.iridium126.createmanaindustry.compat.ysm.internal.pojo.RawYsmModel;
import com.iridium126.createmanaindustry.compat.ysm.internal.resource.YSMBinaryDeserializer;
import com.iridium126.createmanaindustry.compat.ysm.internal.security.YsmCrypt;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.*;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Vector;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmBakedGeometry.*;

/** A parsed resource snapshot. Heavy decoding must be invoked off the game thread. */
public final class YsmCompiledModel {
    private final String digest;
    private final byte[] source;
    private final RawYsmModel resources;
    private final List<Group> roots;
    private YsmCompiledModel(String digest, byte[] source, RawYsmModel resources, List<Group> roots) {
        this.digest = digest; this.source = source; this.resources = resources; this.roots = List.copyOf(roots);
    }
    public static YsmCompiledModel decode(byte[] input) {
        if (input.length > YsmCrypt.MAX_BYTES) throw new IllegalArgumentException("YSM file exceeds snapshot limit");
        byte[] source = input.clone();
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
            RawYsmModel resources;
            try (var parser = new YSMBinaryDeserializer(YsmCrypt.decryptYsmFile(source))) {
                resources = parser.deserializeKeepOpen();
                parser.parseYSMFooter(resources);
                parser.requireEnd();
            }
            var roots = new ArrayList<Group>();
            if (resources.mainEntity.mainModel != null) roots.add(root(digest, "models/main.json", resources.mainEntity.mainModel));
            if (resources.mainEntity.armModel != null) roots.add(root(digest, "models/arm.json", resources.mainEntity.armModel));
            int index = 0;
            for (var entry : resources.vehicles.values()) {
                if (entry.model != null) roots.add(root(digest, "models/vehicle_" + index + ".json", entry.model));
                index++;
            }
            index = 0;
            for (var entry : resources.projectiles.values()) {
                if (entry.model != null) roots.add(root(digest, "models/projectile_" + index + ".json", entry.model));
                index++;
            }
            Set<String> parts = new HashSet<>(); roots.forEach(g -> parts.add(g.root().part()));
            YsmGeometry.validateRoots(roots, parts);
            return new YsmCompiledModel(digest, source, resources, roots);
        } catch (IllegalArgumentException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalArgumentException("Invalid compiled YSM resource: " + failure.getMessage(), failure); }
    }
    public String digest() { return digest; }
    public List<Group> roots() { return roots; }
    public int formatVersion() { return resources.formatVersion; }
    public int sourceSize() { return source.length; }
    public byte[] sourceBytes() { return source.clone(); }
    // Internal export implementation may inspect resources; iotas only carry the digest.
    RawYsmModel resources() { return resources; }

    private static Group root(String digest, String path, RawYsmModel.RawGeometry geometry) {
        int width = dimension(geometry.textureWidth), height = dimension(geometry.textureHeight);
        Map<String, RawYsmModel.RawBone> bones = new LinkedHashMap<>();
        Map<String, List<String>> children = new HashMap<>();
        for (var bone : geometry.bones) {
            if (bone.name == null || bone.name.isBlank() || bones.putIfAbsent(bone.name, bone) != null)
                throw new IllegalArgumentException("Missing or duplicate compiled bone name");
            children.computeIfAbsent(bone.parentName == null ? "" : bone.parentName, k -> new ArrayList<>()).add(bone.name);
        }
        if (bones.size() > YsmGeometry.MAX_NODES) throw new IllegalArgumentException("Too many compiled bones");
        for (String parent : children.keySet()) if (!parent.isEmpty() && !bones.containsKey(parent))
            throw new IllegalArgumentException("Compiled bone parent is missing");
        var visited = new HashSet<String>();
        var top = new ArrayList<Group>();
        for (String name : children.getOrDefault("", List.of())) top.add(bone(name, bones, children, visited, 2, width, height));
        if (visited.size() != bones.size()) throw new IllegalArgumentException("Compiled bone hierarchy contains a cycle");
        JsonObject description = new JsonObject();
        description.addProperty("identifier", geometry.identifier);
        description.addProperty("texture_width", width); description.addProperty("texture_height", height);
        finite(geometry.visibleBoundsWidth); finite(geometry.visibleBoundsHeight);
        description.addProperty("visible_bounds_width", geometry.visibleBoundsWidth);
        description.addProperty("visible_bounds_height", geometry.visibleBoundsHeight);
        if (geometry.visibleBoundsOffset != null && geometry.visibleBoundsOffset.length != 0) {
            if (geometry.visibleBoundsOffset.length != 3) throw new IllegalArgumentException("Invalid compiled visible bounds offset");
            JsonArray offset = new JsonArray();
            for (float v : geometry.visibleBoundsOffset) { finite(v); offset.add(v); }
            description.add("visible_bounds_offset", offset);
        }
        return new Group(path, Vector.ZERO, Vector.ZERO, Vector.ONE, true, List.of(), top,
                new Root(digest, path, width, height, description.toString()), "{}");
    }
    private static Group bone(String name, Map<String, RawYsmModel.RawBone> bones, Map<String, List<String>> children,
            Set<String> visited, int depth, int width, int height) {
        if (depth > YsmGeometry.MAX_DEPTH || !visited.add(name)) throw new IllegalArgumentException("Compiled hierarchy limit or cycle");
        var bone = bones.get(name);
        if (bone.cubes.size() > YsmGeometry.MAX_NODES) throw new IllegalArgumentException("Too many compiled cubes");
        var cubes = new ArrayList<Cube>();
        for (var cube : bone.cubes) {
            var faces = new ArrayList<BakedFace>();
            for (var face : cube.faces) {
                var vertices = new ArrayList<Vertex>();
                for (int i = 0; i < 4; i++) vertices.add(new Vertex(vector(face.positions[i]), face.u[i], face.v[i]));
                faces.add(new BakedFace(vector(face.normal), vertices));
            }
            cubes.add(YsmBakedGeometry.reconstruct(faces, width, height));
        }
        var nested = new ArrayList<Group>();
        for (String child : children.getOrDefault(name, List.of())) nested.add(bone(child, bones, children, visited, depth + 1, width, height));
        Vector p = vector(bone.pivot), r = vector(bone.rotation);
        return new Group(name, new Vector(-p.x(), p.y(), p.z()),
                new Vector(-Math.toDegrees(r.x()), -Math.toDegrees(r.y()), Math.toDegrees(r.z())), Vector.ONE, true, cubes, nested, null, "{}");
    }
    private static Vector vector(float[] values) {
        if (values == null || values.length != 3) throw new IllegalArgumentException("Invalid compiled vector");
        return new Vector(values[0], values[1], values[2]);
    }
    private static int dimension(float value) {
        if (!Float.isFinite(value) || value < 1 || value > 16384 || value != Math.rint(value)) throw new IllegalArgumentException("Invalid compiled texture dimension");
        return (int)value;
    }
    private static void finite(float value) { if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite compiled geometry metadata"); }
}
