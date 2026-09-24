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
    private static final class BoneFrame implements YsmBoneHierarchy.Node {
        private final String name;
        private final Vector pivot, rotation;
        private final List<Cube> cubes;
        private final List<String> childNames;
        private BoneFrame(String name, Vector pivot, Vector rotation, List<Cube> cubes, List<String> childNames) {
            this.name = name; this.pivot = pivot; this.rotation = rotation; this.cubes = cubes; this.childNames = childNames;
        }
        @Override public List<String> childNames() { return childNames; }
        @Override public Group build(List<Group> children) {
            return new Group(name, new Vector(-pivot.x(), pivot.y(), pivot.z()),
                    new Vector(-Math.toDegrees(rotation.x()), -Math.toDegrees(rotation.y()), Math.toDegrees(rotation.z())),
                    Vector.ONE, true, cubes, children, null, "{}");
        }
    }
    private final String digest;
    private final RawYsmModel resources;
    private final List<Group> roots;
    private YsmCompiledModel(String digest, RawYsmModel resources, List<Group> roots) {
        this.digest = digest; this.resources = resources; this.roots = List.copyOf(roots);
    }
    public static YsmCompiledModel decode(byte[] input) {
        byte[] source = input.clone();
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
            YsmCrypt.Decrypted decrypted = YsmCrypt.decryptCrypto3(source);
            RawYsmModel resources;
            try (var parser = new YSMBinaryDeserializer(decrypted.payload(), decrypted.resourceFormat())) {
                resources = parser.deserializeKeepOpen();
            }
            var roots = new ArrayList<Group>();
            if (resources.mainEntity.mainModel != null) roots.add(root(digest, "models/main.json", resources.mainEntity.mainModel));
            if (resources.mainEntity.armModel != null) roots.add(root(digest, "models/arm.json", resources.mainEntity.armModel));
            addEntityRoots(roots, digest, resources.vehicles);
            addEntityRoots(roots, digest, resources.projectiles);
            addEntityRoots(roots, digest, resources.subEntities);
            Set<String> parts = new HashSet<>(); roots.forEach(g -> parts.add(g.root().identity()));
            YsmGeometry.validateRoots(roots, parts);
            return new YsmCompiledModel(digest, resources, roots);
        } catch (IllegalArgumentException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalArgumentException("Invalid compiled YSM resource: " + failure.getMessage(), failure); }
    }
    public String digest() { return digest; }
    public List<Group> roots() { return roots; }
    // Internal export implementation may inspect resources; iotas only carry the digest.
    RawYsmModel resources() { return resources; }

    private static void addEntityRoots(List<Group> roots, String digest, Map<String, RawYsmModel.RawSubEntity> entities) {
        for (var entry : entities.entrySet()) if (entry.getValue().model != null)
            roots.add(root(digest, "models/" + YsmCompiledExporter.safeFilename(entry.getKey() + ".json"), entry.getValue().model));
    }

    private static Group root(String digest, String path, RawYsmModel.RawGeometry geometry) {
        int width = dimension(geometry.textureWidth), height = dimension(geometry.textureHeight);
        Map<String, RawYsmModel.RawBone> bones = new LinkedHashMap<>();
        Map<String, List<String>> children = new HashMap<>();
        for (var bone : geometry.bones) {
            if (bone.name == null || bone.name.isBlank() || bones.putIfAbsent(bone.name, bone) != null)
                throw new IllegalArgumentException("Missing or duplicate compiled bone name");
            children.computeIfAbsent(bone.parentName == null ? "" : bone.parentName, k -> new ArrayList<>()).add(bone.name);
        }
        var top = YsmBoneHierarchy.build(bones, children, name -> boneFrame(name, bones, children, width, height));
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
    private static BoneFrame boneFrame(String name, Map<String, RawYsmModel.RawBone> bones,
            Map<String, List<String>> children, int width, int height) {
        var bone = bones.get(name);
        var cubes = new ArrayList<Cube>();
        for (var cube : bone.cubes) {
            var faces = new ArrayList<BakedFace>();
            for (var face : cube.faces) {
                var vertices = new ArrayList<Vertex>();
                for (int i = 0; i < 4; i++) vertices.add(new Vertex(vector(face.positions[i]), face.u[i], face.v[i]));
                faces.add(new BakedFace(vector(face.normal), vertices));
            }
            try {
                cubes.add(YsmBakedGeometry.reconstructReference(faces, width, height));
            } catch (IllegalArgumentException unsupportedCube) {
                // The reference parser drops a cube when its baked faces cannot be restored.
            }
        }
        return new BoneFrame(name, vector(bone.pivot), vector(bone.rotation), cubes, children.getOrDefault(name, List.of()));
    }
    private static Vector vector(float[] values) {
        if (values == null || values.length != 3) throw new IllegalArgumentException("Invalid compiled vector");
        return new Vector(values[0], values[1], values[2]);
    }
    private static int dimension(float value) {
        if (!Float.isFinite(value) || value < 1 || value > Integer.MAX_VALUE || value != Math.rint(value)) throw new IllegalArgumentException("Invalid compiled texture dimension");
        return (int)value;
    }
    private static void finite(float value) { if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite compiled geometry metadata"); }
}
