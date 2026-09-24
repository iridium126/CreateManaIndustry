package com.iridium126.createmanaindustry.compat.ysm.model;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Immutable resource bundle. The digest covers both paths and contents. */
public final class YsmResourceArchive {
    // The reference parser has no fixed bundle-size or file-count quota. Keep
    // only the maximum size representable by the byte[] based archive format.
    public static final int MAX_BYTES = Integer.MAX_VALUE - 8;
    public static final int MAX_FILES = Integer.MAX_VALUE;
    private static final int MAGIC = 0x59534d01;
    private final SortedMap<String, byte[]> files;
    private final String digest;
    private final int byteSize;
    private final String defaultTexture;

    public YsmResourceArchive(Map<String, byte[]> input) {
        if (input.isEmpty()) throw new IllegalArgumentException("Invalid resource count");
        var copy = new TreeMap<String, byte[]>();
        Set<String> names = new HashSet<>();
        long size = 8;
        for (var entry : input.entrySet()) {
            String path = entry.getKey();
            YsmCompiledExporter.safePath(path);
            if (!names.add(path.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Case-colliding resource paths");
            byte[] bytes = Objects.requireNonNull(entry.getValue(), "Missing resource bytes");
            size += 8L + path.getBytes(StandardCharsets.UTF_8).length + bytes.length;
            if (size > MAX_BYTES) throw new IllegalArgumentException("Resource archive exceeds Java byte-array limit");
            copy.put(path, bytes.clone());
        }
        for (String path : copy.keySet()) {
            for (int slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1))
                if (names.contains(path.substring(0, slash).toLowerCase(Locale.ROOT)))
                    throw new IllegalArgumentException("Resource path is both a directory and a file");
        }
        files = Collections.unmodifiableSortedMap(copy);
        byteSize = (int) size;
        String texture = null;
        if (copy.containsKey("ysm.json")) {
            String config = new String(copy.get("ysm.json"), StandardCharsets.UTF_8);
            if (!Arrays.equals(copy.get("ysm.json"), config.getBytes(StandardCharsets.UTF_8)))
                throw new IllegalArgumentException("Invalid UTF-8 model configuration");
            var json = YsmJson.object(config);
            if (json.has("properties") && json.getAsJsonObject("properties").has("default_texture")) {
                texture = json.getAsJsonObject("properties").get("default_texture").getAsString();
            }
        }
        defaultTexture = texture == null || texture.isBlank() ? "default" : texture;
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            try (var output = new DataOutputStream(new java.security.DigestOutputStream(OutputStream.nullOutputStream(), hash))) { write(output); }
            digest = HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException | IOException impossible) { throw new IllegalStateException(impossible); }
    }

    public String digest() { return digest; }
    public int byteSize() { return byteSize; }
    public String defaultTexture() { return defaultTexture; }
    public Set<String> paths() { return files.keySet(); }
    public static YsmResourceArchive readDirectory(Path directory) throws IOException {
        Path root = directory.toAbsolutePath().normalize().toRealPath();
        var files = new LinkedHashMap<String, byte[]>();
        long size = 0;
        try (var tree = Files.walk(root)) {
            var iterator = tree.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (Files.isSymbolicLink(path) || !path.toRealPath().startsWith(root)) throw new IOException("Linked model resource is not allowed");
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Non-regular model resource");
                if (Files.size(path) > MAX_BYTES - size) throw new IOException("Model resource package exceeds Java byte-array limit");
                byte[] bytes = Files.readAllBytes(path);
                size += bytes.length;
                if (size > MAX_BYTES) throw new IOException("Model resource package exceeds Java byte-array limit");
                files.put(root.relativize(path).toString().replace('\\', '/'), bytes);
            }
        }
        return new YsmResourceArchive(files);
    }
    public byte[] resource(String path) {
        byte[] bytes = files.get(path);
        if (bytes == null) throw new IllegalArgumentException("Missing resource: " + path);
        return bytes.clone();
    }

    /** Selects the configured UV texture for a geometry resource, preferring YSM's default texture. */
    public byte[] textureForPart(String part) {
        JsonObject config = YsmJson.object(new String(resource("ysm.json"), StandardCharsets.UTF_8));
        JsonObject fileTable = config.getAsJsonObject("files");
        JsonObject selected = null;
        if (fileTable != null) {
            for (var entry : fileTable.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject entity = entry.getValue().getAsJsonObject();
                if (entity.has("model") && containsPath(entity.get("model"), part)) {
                    selected = entity;
                    break;
                }
            }
            if (selected == null && fileTable.has("player") && fileTable.get("player").isJsonObject())
                selected = fileTable.getAsJsonObject("player");
        }
        if ((selected == null || !selected.has("texture")) && fileTable != null
                && fileTable.has("player") && fileTable.get("player").isJsonObject())
            selected = fileTable.getAsJsonObject("player");
        if (selected == null || !selected.has("texture")) return null;
        var candidates = new ArrayList<TextureCandidate>();
        collectTextures(selected.get("texture"), "", candidates);
        if (candidates.isEmpty() && fileTable != null && fileTable.has("player")
                && fileTable.get("player").isJsonObject()) {
            JsonObject player = fileTable.getAsJsonObject("player");
            if (player != selected && player.has("texture")) collectTextures(player.get("texture"), "", candidates);
        }
        String preferred = defaultTexture.toLowerCase(Locale.ROOT);
        for (TextureCandidate candidate : candidates) {
            if (candidate.label().equalsIgnoreCase(preferred) || textureName(candidate.path()).equalsIgnoreCase(preferred)) {
                byte[] bytes = imageResource(candidate.path());
                if (bytes != null) return bytes;
            }
        }
        for (TextureCandidate candidate : candidates) {
            byte[] bytes = imageResource(candidate.path());
            if (bytes != null) return bytes;
        }
        return null;
    }

    private boolean containsPath(JsonElement value, String path) {
        if (value == null || value.isJsonNull()) return false;
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) return path.equals(value.getAsString());
        if (value.isJsonArray()) {
            for (JsonElement child : value.getAsJsonArray()) if (containsPath(child, path)) return true;
        } else if (value.isJsonObject()) {
            for (var entry : value.getAsJsonObject().entrySet()) if (containsPath(entry.getValue(), path)) return true;
        }
        return false;
    }

    private static void collectTextures(JsonElement value, String inheritedLabel, List<TextureCandidate> out) {
        if (value == null || value.isJsonNull()) return;
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            out.add(new TextureCandidate(inheritedLabel, value.getAsString()));
            return;
        }
        if (value.isJsonArray()) {
            for (JsonElement child : value.getAsJsonArray()) collectTextures(child, inheritedLabel, out);
            return;
        }
        if (!value.isJsonObject()) return;
        JsonObject object = value.getAsJsonObject();
        String label = inheritedLabel;
        for (String name : List.of("name", "id", "key")) {
            if (object.has(name) && object.get(name).isJsonPrimitive() && object.getAsJsonPrimitive(name).isString()) {
                label = object.get(name).getAsString();
                break;
            }
        }
        if (object.has("uv")) collectTextures(object.get("uv"), label, out);
        else for (var entry : object.entrySet()) {
            if (List.of("name", "id", "key").contains(entry.getKey())) continue;
            collectTextures(entry.getValue(), entry.getKey(), out);
        }
    }

    private byte[] imageResource(String path) {
        if (path == null || !(path.toLowerCase(Locale.ROOT).endsWith(".png")
                || path.toLowerCase(Locale.ROOT).endsWith(".jpg") || path.toLowerCase(Locale.ROOT).endsWith(".jpeg"))) return null;
        return files.containsKey(path) ? resource(path) : null;
    }

    private static String textureName(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return path.substring(slash + 1, dot > slash ? dot : path.length());
    }
    private record TextureCandidate(String label, String path) {}
    public byte[] encode() {
        try {
            var bytes = new ByteArrayOutputStream(byteSize);
            writeTo(bytes);
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
    /** Writes the canonical archive form without allocating a second array for the entire bundle. */
    public void writeTo(OutputStream destination) throws IOException {
        var output = new DataOutputStream(destination);
        write(output);
        output.flush();
    }
    private void write(DataOutputStream output) throws IOException {
        output.writeInt(MAGIC); output.writeInt(files.size());
        for (var entry : files.entrySet()) {
            byte[] name = entry.getKey().getBytes(StandardCharsets.UTF_8);
            output.writeInt(name.length); output.write(name);
            output.writeInt(entry.getValue().length); output.write(entry.getValue());
        }
    }
    public static YsmResourceArchive decode(byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Resource archive exceeds Java byte-array limit");
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC) throw new IllegalArgumentException("Unknown resource archive version");
            int count = input.readInt();
            if (count < 1 || count > input.available() / 8) throw new IllegalArgumentException("Invalid resource count");
            var files = new LinkedHashMap<String, byte[]>();
            for (int i = 0; i < count; i++) {
                int length = input.readInt();
                if (length < 1 || length > input.available()) throw new IllegalArgumentException("Invalid resource path length");
                byte[] name = input.readNBytes(length);
                String path = new String(name, StandardCharsets.UTF_8);
                if (!Arrays.equals(name, path.getBytes(StandardCharsets.UTF_8))) throw new IllegalArgumentException("Invalid UTF-8 resource path");
                length = input.readInt();
                if (length < 0 || length > input.available()) throw new IllegalArgumentException("Truncated resource");
                if (files.putIfAbsent(path, input.readNBytes(length)) != null) throw new IllegalArgumentException("Duplicate resource path");
            }
            if (input.available() != 0) throw new IllegalArgumentException("Trailing resource bytes");
            return new YsmResourceArchive(files);
        } catch (IOException failure) { throw new IllegalArgumentException("Truncated resource archive", failure); }
    }

    /** Called by an approved client export job, off the render thread. Never overwrites a directory. */
    public Path export(Path gameDirectory) throws IOException {
        Path game = gameDirectory.toAbsolutePath().normalize().toRealPath();
        Path root = game;
        for (String part : List.of("exports", "createmanaindustry", "ysm")) {
            root = root.resolve(part);
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Export directory is not a regular directory");
            } else Files.createDirectory(root);
        }
        String name = "model_" + digest.substring(0, 12) + "_" + UUID.randomUUID().toString().replace("-", "");
        Path temporary = Files.createTempDirectory(root, ".pending-");
        boolean published = false;
        try {
            for (var entry : files.entrySet()) {
                Path destination = temporary.resolve(entry.getKey()).normalize();
                if (!destination.startsWith(temporary)) throw new IOException("Resource escaped export directory");
                Files.createDirectories(destination.getParent());
                Files.write(destination, entry.getValue(), StandardOpenOption.CREATE_NEW);
            }
            try {
                YsmResourceArchive written = readDirectory(temporary);
                if (!digest.equals(written.digest())) throw new IOException("Exported model contents changed while writing");
                new YsmPlaintextModel(written);
            } catch (IllegalArgumentException failure) { throw new IOException("Exported model references are invalid", failure); }
            // Same-directory rename publishes the finished tree; no REPLACE_EXISTING is allowed.
            Path destination = root.resolve(name);
            Files.move(temporary, destination);
            published = true;
            return destination;
        } finally {
            if (!published) {
                try (var tree = Files.walk(temporary)) {
                    for (Path path : tree.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
        }
    }
}
