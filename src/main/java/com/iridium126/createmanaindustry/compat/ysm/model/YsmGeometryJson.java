package com.iridium126.createmanaindustry.compat.ysm.model;

import com.google.gson.*;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.*;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Bedrock geometry serialization; unsupported transforms fail before publishing. */
public final class YsmGeometryJson {
    private static final String[] FACES = {"north", "south", "east", "west", "up", "down"};
    public static JsonObject write(Group root) {
        return write(List.of(root));
    }
    /** Replaces selected geometry entries together while retaining every unselected entry in the document. */
    public static JsonObject write(List<Group> roots) {
        if (roots.isEmpty()) throw new IllegalArgumentException("Expected at least one geometry file root");
        Group first = roots.getFirst();
        if (first.root() == null) throw new IllegalArgumentException("Expected geometry file root");
        String part = first.root().part();
        String snapshot = first.root().snapshot();
        JsonObject document = object(first.extraJson());
        if (!document.has("format_version")) document.addProperty("format_version", "1.12.0");
        JsonArray geometries = new JsonArray();
        if (document.has("minecraft:geometry")) {
            for (JsonElement original : document.getAsJsonArray("minecraft:geometry")) geometries.add(original.deepCopy());
        }
        Set<Integer> written = new HashSet<>();
        for (Group root : roots) {
            Root metadata = root.root();
            if (metadata == null || !part.equals(metadata.part()) || !snapshot.equals(metadata.snapshot()))
                throw new IllegalArgumentException("Geometry roots must come from the same source file");
            int index = metadata.geometryIndex();
            if (!written.add(index)) throw new IllegalArgumentException("Duplicate geometry index");
            if (index > geometries.size() || (index < geometries.size() && !geometries.get(index).isJsonObject()))
                throw new IllegalArgumentException("Geometry index is outside the source document");
            JsonObject geometry = index < geometries.size() ? geometries.get(index).getAsJsonObject().deepCopy() : new JsonObject();
            JsonObject description = object(metadata.descriptionJson());
            description.addProperty("texture_width", metadata.textureWidth());
            description.addProperty("texture_height", metadata.textureHeight());
            geometry.add("description", description);
            JsonArray bones = new JsonArray();
            for (Group child : root.children()) bone(child, null, true, bones);
            geometry.add("bones", bones);
            if (index == geometries.size()) geometries.add(geometry);
            else geometries.set(index, geometry);
        }
        document.add("minecraft:geometry", geometries);
        return document;
    }
    private static void bone(Group group, String parent, boolean parentVisible, JsonArray bones) {
        if (!group.scale().equals(Vector.ONE)) throw new IllegalArgumentException("Static group scale needs native client transform support");
        JsonObject bone = object(group.extraJson());
        bone.addProperty("name", group.name());
        if (parent == null) bone.remove("parent"); else bone.addProperty("parent", parent);
        bone.add("pivot", vector(group.pivot())); bone.add("rotation", vector(group.rotation()));
        JsonArray cubes = new JsonArray(), hidden = new JsonArray();
        boolean visible = parentVisible && group.visible();
        for (int index = 0; index < group.cubes().size(); index++) {
            Cube cube = group.cubes().get(index);
            JsonObject encoded = cube(cube);
            encoded.addProperty("createmanaindustry:order", index);
            if (visible && cube.visible()) cubes.add(encoded);
            else {
                if (!cube.visible()) encoded.addProperty("createmanaindustry:visible", false);
                hidden.add(encoded);
            }
        }
        bone.add("cubes", cubes); bones.add(bone);
        bone.remove("createmanaindustry:hidden_cubes");
        if (!hidden.isEmpty()) bone.add("createmanaindustry:hidden_cubes", hidden);
        bone.remove("createmanaindustry:visible");
        if (!group.visible()) bone.addProperty("createmanaindustry:visible", false);
        for (Group child : group.children()) bone(child, group.name(), visible, bones);
    }
    private static JsonObject cube(Cube cube) {
        JsonObject out = object(cube.extraJson());
        Vector origin = cube.origin(), size = cube.size(), scale = cube.scale(), pivot = cube.pivot();
        double inflate = cube.inflate();
        if (!scale.equals(Vector.ONE)) {
            origin = new Vector(pivot.x() + (origin.x() - inflate - pivot.x()) * scale.x(),
                    pivot.y() + (origin.y() - inflate - pivot.y()) * scale.y(), pivot.z() + (origin.z() - inflate - pivot.z()) * scale.z());
            size = new Vector((size.x() + inflate * 2) * scale.x(), (size.y() + inflate * 2) * scale.y(), (size.z() + inflate * 2) * scale.z());
            inflate = 0;
        }
        out.add("origin", vector(origin)); out.add("size", vector(size)); out.add("pivot", vector(pivot));
        out.add("rotation", vector(cube.rotation())); out.addProperty("inflate", inflate);
        // UVs are already resolved per face, including mirroring inherited from a bone.
        out.addProperty("mirror", false);
        JsonObject uv = new JsonObject();
        for (int i = 0; i < 6; i++) {
            Face face = cube.faces().get(i);
            if (!face.visible()) continue;
            JsonObject data = out.has("uv") && out.get("uv").isJsonObject()
                    && out.getAsJsonObject("uv").has(FACES[i]) ? out.getAsJsonObject("uv").getAsJsonObject(FACES[i]).deepCopy() : new JsonObject();
            data.add("uv", numbers(face.u(), face.v())); data.add("uv_size", numbers(face.width(), face.height()));
            if (face.rotation() == 0) data.remove("uv_rotation");
            else data.addProperty("uv_rotation", face.rotation());
            uv.add(FACES[i], data);
        }
        out.add("uv", uv);
        return out;
    }
    private static JsonObject object(String json) {
        return YsmJson.object(json);
    }
    private static JsonArray vector(Vector v) { return numbers(v.x(), v.y(), v.z()); }
    private static JsonArray numbers(double... values) {
        JsonArray out = new JsonArray(); for (double value : values) out.add(value); return out;
    }
    private YsmGeometryJson() {}
}
