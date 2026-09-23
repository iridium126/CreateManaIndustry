package com.iridium126.createmanaindustry.compat.ysm.model;

import com.google.gson.*;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.*;

/** Bedrock geometry serialization; unsupported transforms fail before publishing. */
public final class YsmGeometryJson {
    private static final String[] FACES = {"north", "south", "east", "west", "up", "down"};
    public static JsonObject write(Group root) {
        if (root.root() == null) throw new IllegalArgumentException("Expected geometry file root");
        JsonObject document = object(root.extraJson());
        if (!document.has("format_version")) document.addProperty("format_version", "1.12.0");
        JsonObject geometry = document.has("minecraft:geometry")
                ? document.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject().deepCopy() : new JsonObject();
        JsonObject description = object(root.root().descriptionJson());
        description.addProperty("texture_width", root.root().textureWidth());
        description.addProperty("texture_height", root.root().textureHeight());
        geometry.add("description", description);
        JsonArray bones = new JsonArray();
        for (Group child : root.children()) bone(child, null, true, bones);
        geometry.add("bones", bones);
        JsonArray geometries = new JsonArray(); geometries.add(geometry);
        if (document.has("minecraft:geometry")) {
            JsonArray original = document.getAsJsonArray("minecraft:geometry");
            for (int i = 1; i < original.size(); i++) geometries.add(original.get(i).deepCopy());
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
            if (face.rotation() != 0) throw new IllegalArgumentException("Per-face UV quarter turns require verified native export support");
            JsonObject data = out.has("uv") && out.get("uv").isJsonObject()
                    && out.getAsJsonObject("uv").has(FACES[i]) ? out.getAsJsonObject("uv").getAsJsonObject(FACES[i]).deepCopy() : new JsonObject();
            data.add("uv", numbers(face.u(), face.v())); data.add("uv_size", numbers(face.width(), face.height()));
            data.remove("uv_rotation");
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
