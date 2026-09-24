package com.iridium126.createmanaindustry.compat.ysm;

import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.*;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometryIO;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmModelSnapshot;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmPlaintextModel;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmResourceArchive;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/** World-scoped immutable content-addressed store for model resources and geometry nodes. */
public final class YsmReferenceStore implements AutoCloseable {
    public enum State { MISSING, LOADING, READY, FAILED }
    public record SnapshotResult(State state, YsmModelSnapshot snapshot, List<String> roots, String reason) {
        public SnapshotResult { roots = roots == null ? List.of() : List.copyOf(roots); }
    }
    public record GroupNode(Group value, String cubeList, String groupList, String nodeKey,
            String sourceDigest, String sourcePart) {
        public GroupNode(Group value, String cubeList, String groupList) {
            this(value, cubeList, groupList, "", null, null);
        }
    }
    public record PreviewData(Group group, Cube cube, int textureWidth, int textureHeight, byte[] texture) {
        public PreviewData { texture = texture == null ? null : texture.clone(); }
        @Override public byte[] texture() { return texture == null ? null : texture.clone(); }
    }

    private static final int CUBE_MAGIC = 0x59435501;
    private static final int GROUP_MAGIC = 0x59475201;
    private static final int LIST_MAGIC = 0x594c5301;
    private static final int REF_MAGIC = 0x59524601;
    private static final int MAX_FANOUT = 256;
    private static final long MAX_SOURCE_CACHE_WEIGHT = 256L * 1024 * 1024;
    private static final int MAX_SOURCE_CACHE_COUNT = 8;
    private static final int MAX_WRITE_RESULT_COUNT = 32;

    private final Path root;
    private final Path archives;
    private final Path objects;
    private byte[] namespace;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), task -> {
                var thread = new Thread(task, "CMI YSM reference store");
                thread.setDaemon(true);
                return thread;
            });
    private final LinkedHashMap<String, CompletableFuture<List<String>>> writes = new LinkedHashMap<>(16, .75f, true);
    private final Map<String, CompletableFuture<YsmModelSnapshot>> loads = new HashMap<>();
    private final LinkedHashMap<String, YsmModelSnapshot> sourceCache = new LinkedHashMap<>(16, .75f, true);
    private long sourceCacheWeight;
    private boolean closed;

    public YsmReferenceStore(Path worldRoot) {
        root = worldRoot.toAbsolutePath().normalize().resolve("data").resolve("createmanaindustry").resolve("ysm");
        archives = root.resolve("archives");
        objects = root.resolve("objects");
    }

    /** Starts persistence on a worker and returns a retryable status until every referenced object is durable. */
    public synchronized SnapshotResult persist(YsmModelSnapshot snapshot) {
        requireOpen();
        String digest = snapshot.digest();
        CompletableFuture<List<String>> future = writes.get(digest);
        if (future == null) {
            future = new CompletableFuture<>();
            writes.put(digest, future);
            trimWriteResults();
            var submitted = future;
            try {
                worker.execute(() -> {
                    try {
                        writeArchive(snapshot.archive());
                        List<String> rawRoots = writeGroups(snapshot.roots());
                        var roots = new ArrayList<String>(rawRoots.size());
                        for (int index = 0; index < rawRoots.size(); index++) {
                            Root source = snapshot.roots().get(index).root();
                            roots.add(withPreviewSource(rawRoots.get(index), false, source.snapshot(), source.part()));
                        }
                        submitted.complete(List.copyOf(roots));
                    } catch (Throwable failure) {
                        submitted.completeExceptionally(failure);
                    }
                });
            } catch (RejectedExecutionException busy) {
                writes.remove(digest);
                return new SnapshotResult(State.FAILED, null, List.of(), "YSM reference storage is busy; retry later");
            }
        }
        if (!future.isDone()) return new SnapshotResult(State.LOADING, null, List.of(), "YSM model is being saved; retry later");
        try {
            List<String> roots = future.join();
            rememberSource(snapshot);
            return new SnapshotResult(State.READY, snapshot, roots, "");
        } catch (CompletionException | CancellationException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            writes.remove(digest, future);
            return new SnapshotResult(State.FAILED, null, List.of(), "Unable to save YSM model: " + cause.getMessage());
        }
    }

    /** Loads a source snapshot lazily after restart; expensive archive parsing never runs on the server thread. */
    public synchronized SnapshotResult querySnapshot(String digest) {
        requireOpen();
        requireKey(digest);
        YsmModelSnapshot cached = sourceCache.get(digest);
        if (cached != null) return new SnapshotResult(State.READY, cached, List.of(), "");
        Path archive = archivePath(digest);
        if (!Files.exists(archive, LinkOption.NOFOLLOW_LINKS))
            return new SnapshotResult(State.MISSING, null, List.of(), "Stored YSM model resources are missing; read the model again");
        CompletableFuture<YsmModelSnapshot> future = loads.get(digest);
        if (future == null) {
            future = new CompletableFuture<>();
            loads.put(digest, future);
            var submitted = future;
            try {
                worker.execute(() -> {
                    try {
                        YsmResourceArchive resources = readArchive(digest);
                        YsmModelSnapshot snapshot = YsmModelSnapshot.fromArchive(resources);
                        if (!digest.equals(snapshot.digest())) throw new IOException("Stored source digest does not match archive");
                        submitted.complete(snapshot);
                    } catch (Throwable failure) {
                        submitted.completeExceptionally(failure);
                    }
                });
            } catch (RejectedExecutionException busy) {
                loads.remove(digest);
                return new SnapshotResult(State.FAILED, null, List.of(), "YSM reference storage is busy; retry later");
            }
        }
        if (!future.isDone()) return new SnapshotResult(State.LOADING, null, List.of(), "Stored YSM model is loading; retry later");
        try {
            YsmModelSnapshot snapshot = future.join();
            loads.remove(digest, future);
            rememberSource(snapshot);
            return new SnapshotResult(State.READY, snapshot, List.of(), "");
        } catch (CompletionException | CancellationException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            loads.remove(digest, future);
            return new SnapshotResult(State.FAILED, null, List.of(), "Unable to load stored YSM model: " + cause.getMessage());
        }
    }

    public Cube readCube(String key) {
        ReferenceTarget target = resolveReference(key, true);
        byte[] bytes = readObject(target.nodeKey());
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != CUBE_MAGIC) throw new IOException("Expected a cube node");
            byte[] geometry = input.readAllBytes();
            return YsmGeometryIO.decodeCube(geometry);
        } catch (IOException | IllegalArgumentException failure) {
            throw invalidObject("cube", key, failure);
        }
    }

    public GroupNode readGroup(String key) {
        ReferenceTarget target = resolveReference(key, false);
        byte[] bytes = readObject(target.nodeKey());
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != GROUP_MAGIC) throw new IOException("Expected a group node");
            int headerLength = input.readInt();
            if (headerLength < 1 || headerLength > input.available() - 64) throw new IOException("Invalid group header length");
            Group value = YsmGeometryIO.decodeGroup(input.readNBytes(headerLength));
            if (!value.cubes().isEmpty() || !value.children().isEmpty()) throw new IOException("Group header contains inline children");
            String cubeList = readKey(input);
            String groupList = readKey(input);
            if (input.available() != 0) throw new IOException("Trailing group node bytes");
            String digest = target.sourceDigest();
            String part = target.sourcePart();
            if (digest == null && value.root() != null) {
                digest = value.root().snapshot();
                part = value.root().part();
            }
            return new GroupNode(value, cubeList, groupList, target.nodeKey(), digest, part);
        } catch (IOException | IllegalArgumentException failure) {
            throw invalidObject("group", key, failure);
        }
    }

    public List<String> cubeKeys(GroupNode group) {
        return attachSource(readList(group.cubeList(), ListKind.CUBE), true, group.sourceDigest(), group.sourcePart());
    }
    public List<String> groupKeys(GroupNode group) {
        return attachSource(readList(group.groupList(), ListKind.GROUP), false, group.sourceDigest(), group.sourcePart());
    }

    public String writeCube(Cube cube) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                output.writeInt(CUBE_MAGIC);
                output.write(YsmGeometryIO.encodeCube(cube));
            }
            return writeObject(bytes.toByteArray());
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    public String writeGroup(Group value, List<String> cubeKeys, List<String> groupKeys) {
        Objects.requireNonNull(value);
        if (!value.cubes().isEmpty() || !value.children().isEmpty())
            throw new IllegalArgumentException("Group node metadata must not contain inline children");
        List<String> cubes = rawKeys(cubeKeys, true), groups = rawKeys(groupKeys, false);
        if (value.root() != null && !cubes.isEmpty()) throw new IllegalArgumentException("Geometry roots cannot contain cubes");
        for (String key : cubes) readCube(key);
        for (String key : groups) {
            if (readGroup(key).value().root() != null) throw new IllegalArgumentException("Nested geometry file root");
        }
        String cubeList = writeList(cubes, ListKind.CUBE);
        String groupList = writeList(groups, ListKind.GROUP);
        try {
            byte[] header = YsmGeometryIO.encodeGroup(value);
            var bytes = new ByteArrayOutputStream(12 + header.length + 64);
            try (var output = new DataOutputStream(bytes)) {
                output.writeInt(GROUP_MAGIC); output.writeInt(header.length); output.write(header);
                writeKey(output, cubeList); writeKey(output, groupList);
            }
            return writeObject(bytes.toByteArray());
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    public String writeTree(Cube cube) { return writeCube(cube); }

    /** Gives a freshly stored value the same source preview context as an edited reference. */
    public String writeCubeReference(Cube cube, String sourceKey) {
        String key = writeCube(cube);
        ReferenceTarget source = resolveReference(sourceKey, true);
        return withPreviewSource(key, true, source.sourceDigest(), source.sourcePart());
    }

    /** Gives a freshly stored value the same source preview context as an edited reference. */
    public String writeGroupReference(Group value, List<String> cubeKeys, List<String> groupKeys, String sourceKey) {
        String key = writeGroup(value, cubeKeys, groupKeys);
        ReferenceTarget source = resolveReference(sourceKey, false);
        String digest = source.sourceDigest();
        String part = source.sourcePart();
        if (digest == null && value.root() != null) {
            digest = value.root().snapshot();
            part = value.root().part();
        }
        return withPreviewSource(key, false, digest, part);
    }

    /** Stores a standalone group value and preserves provenance when it is a geometry-file root. */
    public String writeTreeReference(Group value) {
        String key = writeTree(value);
        Root source = value.root();
        return source == null ? key : withPreviewSource(key, false, source.snapshot(), source.part());
    }

    /** Builds a full preview on demand. A null texture means the geometry has no source archive. */
    public PreviewData preview(String key, boolean cube) {
        ReferenceTarget target = resolveReference(key, cube);
        Cube cubeValue = cube ? readCube(key) : null;
        Group groupValue = cube ? null : materializeGroups(List.of(key)).getFirst();
        String digest = target.sourceDigest();
        String part = target.sourcePart();
        if (digest == null && groupValue != null && groupValue.root() != null) {
            digest = groupValue.root().snapshot();
            part = groupValue.root().part();
        }
        if (digest == null) return new PreviewData(groupValue, cubeValue, 64, 64, null);
        SnapshotResult source = querySnapshot(digest);
        if (source.state() != State.READY) throw new IllegalStateException(source.reason());
        String selectedPart = part;
        Root root = source.snapshot().roots().stream().map(Group::root).filter(Objects::nonNull)
                .filter(value -> value.part().equals(selectedPart)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Referenced YSM geometry part is missing from its source"));
        return new PreviewData(groupValue, cubeValue, root.textureWidth(), root.textureHeight(),
                source.snapshot().archive().textureForPart(selectedPart));
    }

    private ReferenceTarget resolveReference(String key, boolean cube) {
        byte[] bytes = readObject(key);
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int magic = input.readInt();
            if (magic == REF_MAGIC) {
                int type = input.readUnsignedByte();
                if (type != (cube ? 2 : 1)) throw new IOException("YSM reference kind mismatch");
                String node = readKey(input);
                String digest = input.readUTF();
                String part = readText(input);
                if (input.available() != 0 || !digest.matches("[0-9a-f]{64}") || part.isBlank())
                    throw new IOException("Invalid YSM preview reference");
                new Root(digest, part, 64, 64, "{}");
                byte[] target = readObject(node);
                int expected = cube ? CUBE_MAGIC : GROUP_MAGIC;
                if (target.length < 4 || new DataInputStream(new ByteArrayInputStream(target)).readInt() != expected)
                    throw new IOException("Referenced geometry node has the wrong kind");
                return new ReferenceTarget(node, cube, digest, part);
            }
            if (magic != (cube ? CUBE_MAGIC : GROUP_MAGIC))
                throw new IOException("Expected a " + (cube ? "cube" : "group") + " reference");
            return new ReferenceTarget(key, cube, null, null);
        } catch (IOException failure) {
            throw invalidObject(cube ? "cube reference" : "group reference", key, failure);
        }
    }

    private List<String> rawKeys(List<String> keys, boolean cube) {
        var result = new ArrayList<String>(keys.size());
        for (String key : keys) result.add(resolveReference(key, cube).nodeKey());
        return List.copyOf(result);
    }

    private List<String> attachSource(List<String> keys, boolean cube, String digest, String part) {
        if (digest == null) return keys;
        var result = new ArrayList<String>(keys.size());
        for (String key : keys) result.add(withPreviewSource(key, cube, digest, part));
        return List.copyOf(result);
    }

    private String withPreviewSource(String nodeKey, boolean cube, String digest, String part) {
        if (digest == null) return nodeKey;
        requireKey(digest);
        if (part == null || part.isBlank()) throw new IllegalArgumentException("Missing YSM source part for preview handle");
        try {
            new Root(digest, part, 64, 64, "{}");
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                output.writeInt(REF_MAGIC);
                output.writeByte(cube ? 2 : 1);
                writeKey(output, nodeKey);
                output.writeUTF(digest);
                writeText(output, part);
            }
            return writeObject(bytes.toByteArray());
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void writeText(DataOutputStream output, String text) throws IOException {
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        output.writeInt(bytes.length); output.write(bytes);
    }
    private static String readText(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > input.available()) throw new IOException("Invalid YSM preview source path length");
        byte[] bytes = input.readNBytes(length);
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        if (!Arrays.equals(bytes, text.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            throw new IOException("Invalid YSM preview source path encoding");
        return text;
    }

    /** Stores an inline geometry tree bottom-up and returns its immutable root key. */
    public String writeTree(Group rootGroup) {
        record Frame(Group value, boolean expanded) {}
        var keys = new IdentityHashMap<Group, String>();
        var pending = new ArrayDeque<Frame>();
        pending.push(new Frame(rootGroup, false));
        while (!pending.isEmpty()) {
            Frame frame = pending.pop();
            if (!frame.expanded()) {
                pending.push(new Frame(frame.value(), true));
                List<Group> children = frame.value().children();
                for (int index = children.size() - 1; index >= 0; index--)
                    pending.push(new Frame(children.get(index), false));
                continue;
            }
            var cubeKeys = new ArrayList<String>(frame.value().cubes().size());
            frame.value().cubes().forEach(cube -> cubeKeys.add(writeCube(cube)));
            var groupKeys = new ArrayList<String>(frame.value().children().size());
            frame.value().children().forEach(child -> groupKeys.add(keys.get(child)));
            Group value = frame.value();
            Group header = new Group(value.name(), value.pivot(), value.rotation(), value.scale(), value.visible(),
                    List.of(), List.of(), value.root(), value.extraJson());
            keys.put(value, writeGroup(header, cubeKeys, groupKeys));
        }
        return keys.get(rootGroup);
    }

    /** Reconstructs requested roots iteratively so deep models do not consume the Java call stack. */
    public List<Group> materializeGroups(List<String> rootKeys) {
        record Frame(String key, boolean expanded) {}
        var values = new HashMap<String, Group>();
        var pending = new ArrayDeque<Frame>();
        for (int index = rootKeys.size() - 1; index >= 0; index--) pending.push(new Frame(rootKeys.get(index), false));
        while (!pending.isEmpty()) {
            Frame frame = pending.pop();
            if (values.containsKey(frame.key())) continue;
            GroupNode node = readGroup(frame.key());
            List<String> children = groupKeys(node);
            if (!frame.expanded()) {
                pending.push(new Frame(frame.key(), true));
                for (int index = children.size() - 1; index >= 0; index--)
                    if (!values.containsKey(children.get(index))) pending.push(new Frame(children.get(index), false));
                continue;
            }
            var cubes = new ArrayList<Cube>();
            for (String cubeKey : cubeKeys(node)) cubes.add(readCube(cubeKey));
            var groups = new ArrayList<Group>(children.size());
            for (String child : children) {
                Group group = values.get(child);
                if (group == null) throw new IllegalArgumentException("Stored group tree is incomplete");
                groups.add(group);
            }
            Group meta = node.value();
            values.put(frame.key(), new Group(meta.name(), meta.pivot(), meta.rotation(), meta.scale(), meta.visible(),
                    cubes, groups, meta.root(), meta.extraJson()));
        }
        return rootKeys.stream().map(key -> {
            Group group = values.get(key);
            if (group == null) throw new IllegalArgumentException("Stored root group is missing");
            return group;
        }).toList();
    }

    private List<String> writeGroups(List<Group> values) {
        var keys = new ArrayList<String>(values.size());
        for (Group value : values) keys.add(writeTree(value));
        return List.copyOf(keys);
    }

    private String writeList(List<String> keys, ListKind kind) {
        for (String key : keys) requireKey(key);
        var level = new ArrayList<ListEntry>();
        if (keys.isEmpty()) level.add(new ListEntry(writeListNode(kind, List.of(), null), 0));
        else {
            for (int offset = 0; offset < keys.size(); offset += MAX_FANOUT) {
                int end = Math.min(keys.size(), offset + MAX_FANOUT);
                level.add(new ListEntry(writeListNode(kind, keys.subList(offset, end), null), end - offset));
            }
        }
        while (level.size() > 1) {
            var parent = new ArrayList<ListEntry>();
            for (int offset = 0; offset < level.size(); offset += MAX_FANOUT) {
                int end = Math.min(level.size(), offset + MAX_FANOUT);
                List<ListEntry> children = List.copyOf(level.subList(offset, end));
                int count = 0;
                for (ListEntry child : children) count = Math.addExact(count, child.size());
                parent.add(new ListEntry(writeListNode(kind, List.of(), children), count));
            }
            level = parent;
        }
        return level.getFirst().key();
    }

    private String writeListNode(ListKind kind, List<String> leaf, List<ListEntry> branch) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                output.writeInt(LIST_MAGIC); output.writeByte(kind.code());
                output.writeBoolean(branch != null);
                if (branch == null) {
                    output.writeInt(leaf.size());
                    for (String key : leaf) writeKey(output, key);
                } else {
                    output.writeInt(branch.size());
                    for (ListEntry entry : branch) { writeKey(output, entry.key()); output.writeInt(entry.size()); }
                }
            }
            return writeObject(bytes.toByteArray());
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    private List<String> readList(String rootKey, ListKind expected) {
        record Pending(String key, int expectedSize) {}
        var output = new ArrayList<String>();
        var pending = new ArrayDeque<Pending>();
        pending.push(new Pending(rootKey, -1));
        while (!pending.isEmpty()) {
            Pending item = pending.pop();
            ListNode node = readListNode(item.key(), expected);
            if (item.expectedSize() >= 0 && node.size() != item.expectedSize())
                throw invalidObject("list", item.key(), new IOException("List subtree size mismatch"));
            if (node.leaf()) output.addAll(node.values());
            else {
                List<ListEntry> children = node.children();
                for (int index = children.size() - 1; index >= 0; index--) {
                    ListEntry child = children.get(index);
                    pending.push(new Pending(child.key(), child.size()));
                }
            }
        }
        return List.copyOf(output);
    }

    private ListNode readListNode(String key, ListKind expected) {
        byte[] bytes = readObject(key);
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != LIST_MAGIC) throw new IOException("Expected a list node");
            ListKind kind = ListKind.fromCode(input.readUnsignedByte());
            if (kind != expected) throw new IOException("List node kind mismatch");
            boolean branch = input.readBoolean();
            int count = input.readInt();
            if (count < 0 || count > MAX_FANOUT) throw new IOException("Invalid list node length");
            if (!branch) {
                if (count > input.available() / 32) throw new IOException("Truncated list node");
                var values = new ArrayList<String>(count);
                for (int index = 0; index < count; index++) values.add(readKey(input));
                if (input.available() != 0) throw new IOException("Trailing list node bytes");
                return new ListNode(kind, true, values, List.of(), count);
            }
            if (count == 0 || count > input.available() / 36) throw new IOException("Invalid list branch length");
            var children = new ArrayList<ListEntry>(count);
            int total = 0;
            for (int index = 0; index < count; index++) {
                String child = readKey(input); int size = input.readInt();
                if (size < 1) throw new IOException("Invalid list subtree size");
                total = Math.addExact(total, size);
                children.add(new ListEntry(child, size));
            }
            if (input.available() != 0) throw new IOException("Trailing list branch bytes");
            return new ListNode(kind, false, List.of(), children, total);
        } catch (IOException | ArithmeticException failure) {
            throw invalidObject("list", key, failure);
        }
    }

    private void writeArchive(YsmResourceArchive archive) throws IOException {
        writeGenerated(archivePath(archive.digest()), archive.digest(), archive::writeTo);
    }

    private YsmResourceArchive readArchive(String digest) throws IOException {
        byte[] payload = readFile(archivePath(digest), digest);
        YsmResourceArchive archive = YsmResourceArchive.decode(payload);
        if (!digest.equals(archive.digest())) throw new IOException("Stored archive digest mismatch");
        return archive;
    }

    private String writeObject(byte[] payload) {
        String key = hashObject(payload);
        try { writeObjectFile(objectPath(key), payload, key); }
        catch (IOException failure) { throw new IllegalStateException("Unable to persist YSM geometry node: " + failure.getMessage(), failure); }
        return key;
    }

    private byte[] readObject(String key) {
        requireKey(key);
        try {
            Path path = objectPath(key);
            verifyParents(path.getParent());
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new NoSuchFileException(path.toString());
            if (!key.equals(hashObjectFile(path))) throw new IOException("SHA-256 verification failed");
            return Files.readAllBytes(path);
        }
        catch (IOException failure) { throw new IllegalArgumentException("Unable to read YSM reference " + key.substring(0, 12) + ": " + failure.getMessage(), failure); }
    }

    private byte[] readFile(Path path, String expectedHash) throws IOException {
        verifyParents(path.getParent());
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new NoSuchFileException(path.toString());
        if (!expectedHash.equals(hashFile(path))) throw new IOException("SHA-256 verification failed");
        byte[] bytes = Files.readAllBytes(path);
        return bytes;
    }

    private synchronized void writeObjectFile(Path path, byte[] bytes, String expectedHash) throws IOException {
        if (!expectedHash.equals(hashObject(bytes))) throw new IOException("Refusing content with the wrong world-scoped SHA-256 key");
        ensureDirectory(path.getParent());
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || !expectedHash.equals(hashObjectFile(path))) throw new IOException("Existing object failed SHA-256 verification");
            return;
        }
        Path temporary = Files.createTempFile(path.getParent(), ".pending-", ".tmp");
        boolean moved = false;
        try {
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            if (!expectedHash.equals(hashObjectFile(temporary))) throw new IOException("Temporary object verification failed");
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, path); }
            moved = true;
        } finally { if (!moved) Files.deleteIfExists(temporary); }
    }

    private synchronized void writeGenerated(Path path, String expectedHash, FileWriter writer) throws IOException {
        ensureDirectory(path.getParent());
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || !expectedHash.equals(hashFile(path))) throw new IOException("Existing object failed SHA-256 verification");
            return;
        }
        Path temporary = Files.createTempFile(path.getParent(), ".pending-", ".tmp");
        boolean moved = false;
        try {
            try (OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING)) { writer.write(output); }
            if (!expectedHash.equals(hashFile(temporary))) throw new IOException("Temporary object verification failed");
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, path); }
            moved = true;
        } finally { if (!moved) Files.deleteIfExists(temporary); }
    }

    private void ensureDirectory(Path directory) throws IOException {
        Path normalized = directory.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) throw new IOException("YSM storage path escaped the world directory");
        Path current = root;
        if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("YSM storage root is not a regular directory");
        } else Files.createDirectories(current);
        Path relative = root.relativize(normalized);
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("YSM storage path contains a linked or non-directory entry");
            } else Files.createDirectory(current);
        }
    }

    private void verifyParents(Path directory) throws IOException {
        Path normalized = directory.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) throw new IOException("YSM storage path escaped the world directory");
        Path current = root;
        if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(current)
                || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) throw new IOException("YSM storage root is unavailable");
        for (Path segment : root.relativize(normalized)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("YSM storage path contains a linked or non-directory entry");
        }
    }

    private Path archivePath(String digest) {
        requireKey(digest);
        return archives.resolve(digest.substring(0, 2)).resolve(digest + ".bin");
    }
    private Path objectPath(String key) {
        requireKey(key);
        return objects.resolve(key.substring(0, 2)).resolve(key + ".bin");
    }

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String hashFile(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                for (int read; (read = input.read(buffer)) >= 0;) if (read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private String hashObject(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(namespaceId()); digest.update(bytes);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        catch (IOException failure) { throw new IllegalStateException("Unable to load YSM world namespace: " + failure.getMessage(), failure); }
    }
    private String hashObjectFile(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(namespaceId());
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                for (int read; (read = input.read(buffer)) >= 0;) if (read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private synchronized byte[] namespaceId() throws IOException {
        if (namespace != null) return namespace;
        ensureDirectory(root);
        Path path = root.resolve("namespace.bin");
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("YSM world namespace is not a regular file");
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length != 32) throw new IOException("Invalid YSM world namespace length");
            namespace = bytes;
            return namespace;
        }
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        Path temporary = Files.createTempFile(root, ".namespace-", ".tmp");
        boolean moved = false;
        try {
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, path); }
            moved = true; namespace = bytes; return namespace;
        } finally { if (!moved) Files.deleteIfExists(temporary); }
    }
    private static void writeKey(DataOutputStream output, String key) throws IOException {
        requireKey(key); output.write(HexFormat.of().parseHex(key));
    }
    private static String readKey(DataInputStream input) throws IOException {
        byte[] key = input.readNBytes(32);
        if (key.length != 32) throw new EOFException("Truncated node key");
        return HexFormat.of().formatHex(key);
    }
    private static void requireKey(String key) {
        if (key == null || !key.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid YSM node key");
    }
    private static IllegalArgumentException invalidObject(String kind, String key, Exception failure) {
        return new IllegalArgumentException("Invalid stored YSM " + kind + " " + key.substring(0, 12) + ": " + failure.getMessage(), failure);
    }

    private synchronized void rememberSource(YsmModelSnapshot snapshot) {
        if (snapshot.weight() > MAX_SOURCE_CACHE_WEIGHT) {
            YsmModelSnapshot previous = sourceCache.remove(snapshot.digest());
            if (previous != null) sourceCacheWeight -= previous.weight();
            return;
        }
        YsmModelSnapshot previous = sourceCache.put(snapshot.digest(), snapshot);
        if (previous != null) sourceCacheWeight -= previous.weight();
        sourceCacheWeight += snapshot.weight();
        var iterator = sourceCache.entrySet().iterator();
        while (!sourceCache.isEmpty() && (sourceCache.size() > MAX_SOURCE_CACHE_COUNT || sourceCacheWeight > MAX_SOURCE_CACHE_WEIGHT)) {
            var eldest = iterator.next(); sourceCacheWeight -= eldest.getValue().weight(); iterator.remove();
        }
    }

    private void trimWriteResults() {
        while (writes.size() > MAX_WRITE_RESULT_COUNT) {
            var iterator = writes.entrySet().iterator();
            boolean removed = false;
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (entry.getValue().isDone()) { iterator.remove(); removed = true; break; }
            }
            if (!removed) break;
        }
    }

    @FunctionalInterface private interface FileWriter { void write(OutputStream output) throws IOException; }

    private void requireOpen() { if (closed) throw new IllegalStateException("YSM reference store is closed"); }
    @Override public synchronized void close() {
        closed = true;
        writes.values().forEach(future -> future.cancel(false));
        loads.values().forEach(future -> future.cancel(false));
        writes.clear(); loads.clear(); sourceCache.clear(); sourceCacheWeight = 0;
        worker.shutdownNow();
    }

    private enum ListKind {
        CUBE(1), GROUP(2);
        private final int code;
        ListKind(int code) { this.code = code; }
        int code() { return code; }
        static ListKind fromCode(int code) throws IOException {
            for (ListKind kind : values()) if (kind.code == code) return kind;
            throw new IOException("Invalid list kind");
        }
    }
    private record ListEntry(String key, int size) {}
    private record ListNode(ListKind kind, boolean leaf, List<String> values, List<ListEntry> children, int size) {}
    private record ReferenceTarget(String nodeKey, boolean cube, String sourceDigest, String sourcePart) {}
}
