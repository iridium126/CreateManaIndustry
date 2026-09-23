package com.iridium126.createmanaindustry.compat.ysm.model;

import com.google.gson.*;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Group;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** A complete immutable plaintext source snapshot; untouched resources are byte-for-byte preserved. */
public final class YsmPlaintextModel {
    private final YsmResourceArchive source;
    private final List<Group> roots;
    public YsmPlaintextModel(YsmResourceArchive source) {
        this.source = source;
        validateReferences(source);
        List<Group> geometry = new ArrayList<>();
        for (String path : source.paths()) {
            if (!path.endsWith(".json") || path.equals("ysm.json")) continue;
            byte[] bytes = source.resource(path);
            String text = utf8(bytes);
            if (YsmJson.resource(text).has("minecraft:geometry")) geometry.add(YsmGeometryReader.read(source.digest(), path, text));
        }
        if (geometry.isEmpty()) throw new IllegalArgumentException("No supported geometry files in model");
        roots = List.copyOf(geometry);
        YsmGeometry.validateRoots(roots, parts());
    }
    public String digest() { return source.digest(); }
    public List<Group> roots() { return roots; }
    public YsmResourceArchive source() { return source; }
    public YsmResourceArchive apply(List<Group> edited) {
        if (!digest().equals(YsmGeometry.validateRoots(edited, parts()))) throw new IllegalArgumentException("Source snapshot mismatch");
        var files = new LinkedHashMap<String, byte[]>();
        for (String path : source.paths()) files.put(path, source.resource(path));
        for (Group root : edited) files.put(root.root().part(), YsmGeometryJson.write(root).toString().getBytes(StandardCharsets.UTF_8));
        var archive = new YsmResourceArchive(files);
        validateReferences(archive);
        return archive;
    }
    private Set<String> parts() { var result = new HashSet<String>(); for (Group root : roots) result.add(root.root().part()); return result; }
    public static void validateReferences(YsmResourceArchive source) {
        try {
            JsonObject config = YsmJson.object(utf8(source.resource("ysm.json")));
            JsonObject files = config.getAsJsonObject("files");
            if (files == null || !files.has("player")) throw new IllegalArgumentException("Missing player resource configuration");
            entity(source, files.getAsJsonObject("player"));
            for (String kind : List.of("vehicles", "projectiles", "sub_entities")) if (files.has(kind)) {
                JsonElement collection = files.get(kind);
                if (collection.isJsonArray()) {
                    for (JsonElement item : collection.getAsJsonArray()) entity(source, item.getAsJsonObject());
                } else if (collection.isJsonObject()) {
                    for (var item : collection.getAsJsonObject().entrySet()) entity(source, item.getValue().getAsJsonObject());
                } else throw new IllegalArgumentException("Invalid entity resource configuration");
            }
            if (config.has("properties")) {
                JsonObject properties = config.getAsJsonObject("properties");
                for (String key : List.of("gui_background", "gui_foreground")) if (properties.has(key)) reference(source, properties.get(key));
            }
            if (config.has("metadata") && config.getAsJsonObject("metadata").has("authors")) {
                for (JsonElement author : config.getAsJsonObject("metadata").getAsJsonArray("authors"))
                    if (author.getAsJsonObject().has("avatar")) reference(source, author.getAsJsonObject().get("avatar"));
            }
        } catch (IllegalStateException | NullPointerException | ClassCastException failure) {
            throw new IllegalArgumentException("Malformed model resource references", failure);
        }
    }
    private static void entity(YsmResourceArchive source, JsonObject entity) {
        for (String key : List.of("model", "texture", "animation", "animation_controllers", "controller"))
            if (entity.has(key)) reference(source, entity.get(key));
    }
    private static void reference(YsmResourceArchive source, JsonElement value) {
        if (value.isJsonObject()) { for (var entry : value.getAsJsonObject().entrySet()) reference(source, entry.getValue()); }
        else if (value.isJsonArray()) { for (var item : value.getAsJsonArray()) reference(source, item); }
        else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String path = value.getAsString(); YsmCompiledExporter.safePath(path);
            if (!source.paths().contains(path)) throw new IllegalArgumentException("Missing referenced resource: " + path);
        } else throw new IllegalArgumentException("Invalid resource reference");
    }
    private static String utf8(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!Arrays.equals(bytes, text.getBytes(StandardCharsets.UTF_8))) throw new IllegalArgumentException("Invalid UTF-8 model resource");
        return text;
    }
}
