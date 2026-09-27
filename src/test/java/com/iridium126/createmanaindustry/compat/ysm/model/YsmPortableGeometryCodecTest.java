package com.iridium126.createmanaindustry.compat.ysm.model;

import com.iridium126.createmanaindustry.compat.ysm.YsmReferenceStore;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Group;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class YsmPortableGeometryCodecTest {
    @Test
    void cubeAndRecursiveGroupMoveBetweenIndependentWorldStores() throws Exception {
        Path sourceWorld = Files.createTempDirectory("cmi-ysm-portable-source-");
        Path targetWorld = Files.createTempDirectory("cmi-ysm-portable-target-");
        try (var source = new YsmReferenceStore(sourceWorld); var target = new YsmReferenceStore(targetWorld)) {
            var snapshot = YsmModelSnapshot.fromArchive(sampleArchive());
            var persisted = awaitPersist(source, snapshot);
            String sourceRoot = persisted.roots().getFirst();
            String bone = source.groupKeys(source.readGroup(sourceRoot)).getFirst();
            String sourceCube = source.cubeKeys(source.readGroup(bone)).getFirst();

            String cubeText = YsmPortableGeometryCodec.encodeCube(source.exportPortableCube(sourceCube));
            String groupText = YsmPortableGeometryCodec.encodeGroup(source.exportPortableGroup(sourceRoot));
            assertFalse(cubeText.contains(sourceCube), "Cube text must not contain the world-scoped key");
            assertFalse(groupText.contains(sourceRoot), "Group text must not contain the world-scoped key");

            String targetCube = target.importPortableCube(YsmPortableGeometryCodec.decodeCube(cubeText));
            String targetRoot = target.importPortableGroup(YsmPortableGeometryCodec.decodeGroup(groupText));
            assertNotEquals(sourceCube, targetCube, "The second save must allocate its own cube key");
            assertNotEquals(sourceRoot, targetRoot, "The second save must allocate its own group key");
            assertEquals(source.readCube(sourceCube), target.readCube(targetCube));
            assertEquals(source.materializeGroups(java.util.List.of(sourceRoot)).getFirst(),
                    target.materializeGroups(java.util.List.of(targetRoot)).getFirst());
            assertArrayEquals(new byte[]{1, 2, 3, 4}, target.preview(targetCube, true).texture());
            assertArrayEquals(new byte[]{1, 2, 3, 4}, target.preview(targetRoot, false).texture());
        } finally {
            deleteTree(sourceWorld);
            deleteTree(targetWorld);
        }
    }

    @Test
    void rejectsUnknownKindsVersionsTruncationAndMismatchedSourceDigests() throws Exception {
        var snapshot = YsmModelSnapshot.fromArchive(sampleArchive());
        var root = snapshot.roots().getFirst();
        var cube = root.children().getFirst().cubes().getFirst();
        String cubeText = YsmPortableGeometryCodec.encodeCube(new YsmPortableGeometryCodec.CubeData(cube,
                new YsmPortableGeometryCodec.Source(snapshot.archive(), root.root().part(), root.root().geometryIndex())));
        assertThrows(IllegalArgumentException.class, () -> YsmPortableGeometryCodec.decodeGroup(
                YsmPortableGeometryCodec.groupPrefix() + cubeText.substring(YsmPortableGeometryCodec.cubePrefix().length())));
        assertThrows(IllegalArgumentException.class, () -> YsmPortableGeometryCodec.decodeCube(
                cubeText.substring(0, cubeText.length() - 4)));
        assertThrows(IllegalArgumentException.class, () -> YsmPortableGeometryCodec.decodeCube(
                appendCompressedByte(cubeText, YsmPortableGeometryCodec.cubePrefix())));
        assertThrows(IllegalArgumentException.class, () -> YsmPortableGeometryCodec.decodeGroup(unknownVersionPayload()));
        assertThrows(IllegalArgumentException.class, () -> YsmPortableGeometryCodec.decodeCube(
                invalidResourcePathPayload(YsmGeometryIO.encodeCube(cube))));

        var oldRoot = root.root();
        var mismatchedRoot = new YsmGeometry.Root("f".repeat(64), oldRoot.part(), oldRoot.textureWidth(),
                oldRoot.textureHeight(), oldRoot.descriptionJson(), oldRoot.geometryIndex());
        var mismatchedGroup = new Group(root.name(), root.pivot(), root.rotation(), root.scale(), root.visible(),
                root.cubes(), root.children(), mismatchedRoot, root.extraJson());
        var source = new YsmPortableGeometryCodec.Source(snapshot.archive(), oldRoot.part(), oldRoot.geometryIndex());
        assertThrows(IllegalArgumentException.class, () -> YsmPortableGeometryCodec.encodeGroup(
                new YsmPortableGeometryCodec.GroupData(mismatchedGroup, source)));
        assertThrows(IllegalArgumentException.class, () -> new YsmResourceArchive(Map.of("../escape", new byte[]{1})));
    }

    private static String unknownVersionPayload() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(new DeflaterOutputStream(bytes))) {
            output.writeInt(0x434d4959);
            output.writeByte(255);
            output.writeByte(2);
        }
        return YsmPortableGeometryCodec.groupPrefix()
                + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
    }

    private static String invalidResourcePathPayload(byte[] geometry) throws Exception {
        byte[] path = "../escape".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream archiveBytes = new ByteArrayOutputStream();
        try (DataOutputStream archive = new DataOutputStream(archiveBytes)) {
            archive.writeInt(0x59534d01);
            archive.writeInt(1);
            archive.writeInt(path.length);
            archive.write(path);
            archive.writeInt(1);
            archive.writeByte(1);
        }
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(new DeflaterOutputStream(payload))) {
            output.writeInt(0x434d4959);
            output.writeByte(1);
            output.writeByte(1);
            output.writeInt(geometry.length);
            output.write(geometry);
            output.writeBoolean(true);
            output.writeInt(archiveBytes.size());
            output.write(archiveBytes.toByteArray());
            byte[] part = "geometry/player.json".getBytes(StandardCharsets.UTF_8);
            output.writeInt(part.length);
            output.write(part);
            output.writeInt(0);
        }
        return YsmPortableGeometryCodec.cubePrefix()
                + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray());
    }

    private static String appendCompressedByte(String token, String prefix) {
        byte[] compressed = java.util.Base64.getUrlDecoder().decode(token.substring(prefix.length()));
        byte[] trailing = java.util.Arrays.copyOf(compressed, compressed.length + 1);
        trailing[trailing.length - 1] = 42;
        return prefix + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(trailing);
    }

    private static YsmReferenceStore.SnapshotResult awaitPersist(YsmReferenceStore store, YsmModelSnapshot snapshot)
            throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            var result = store.persist(snapshot);
            if (result.state() == YsmReferenceStore.State.READY || result.state() == YsmReferenceStore.State.FAILED)
                return result;
            Thread.sleep(5);
        }
        fail("Timed out persisting source model");
        throw new AssertionError();
    }

    private static YsmResourceArchive sampleArchive() {
        String manifest = "{\"files\":{\"player\":{\"model\":\"geometry/player.json\",\"texture\":\"textures/player.png\"}}}";
        String geometry = "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{\"description\":{\"identifier\":\"geometry.test\",\"texture_width\":64,\"texture_height\":64},\"bones\":[{\"name\":\"root\",\"pivot\":[0,0,0],\"cubes\":[{\"origin\":[0,0,0],\"size\":[1,1,1],\"uv\":{\"north\":{\"uv\":[0,0],\"uv_size\":[1,1]}}}]}]}]}";
        return new YsmResourceArchive(Map.of(
                "ysm.json", manifest.getBytes(StandardCharsets.UTF_8),
                "geometry/player.json", geometry.getBytes(StandardCharsets.UTF_8),
                "textures/player.png", new byte[]{1, 2, 3, 4}));
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
