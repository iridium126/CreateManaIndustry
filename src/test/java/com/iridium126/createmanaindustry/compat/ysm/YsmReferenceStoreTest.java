package com.iridium126.createmanaindustry.compat.ysm;

import static org.junit.jupiter.api.Assertions.*;

import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmModelSnapshot;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmResourceArchive;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class YsmReferenceStoreTest {
    @Test
    void persistsDeduplicatedImmutableNodesAndSourceAcrossRestart() throws Exception {
        Path world = Files.createTempDirectory("cmi-ysm-reference-test-");
        try {
            YsmModelSnapshot snapshot = YsmModelSnapshot.fromArchive(sampleArchive());
            String oldRoot;
            String newRoot;
            String oldCube;
            String newCube;
            try (var store = new YsmReferenceStore(world)) {
                var saved = awaitPersist(store, snapshot);
                assertEquals(1, saved.roots().size());
                oldRoot = saved.roots().getFirst();

                var rootNode = store.readGroup(oldRoot);
                String boneKey = store.groupKeys(rootNode).getFirst();
                var boneNode = store.readGroup(boneKey);
                oldCube = store.cubeKeys(boneNode).getFirst();
                var cube = store.readCube(oldCube);
                assertEquals(oldCube, store.writeCubeReference(cube, oldCube), "identical geometry and source should share its preview handle");
                assertArrayEquals(new byte[]{1, 2, 3, 4}, store.preview(oldCube, true).texture(),
                        "cube references inherit the source texture context");

                var edited = new YsmGeometry.Cube(new YsmGeometry.Vector(7, 8, 9), cube.size(), cube.pivot(),
                        cube.rotation(), cube.scale(), cube.inflate(), cube.visible(), cube.faces(), cube.extraJson());
                newCube = store.writeCube(edited);
                String newBone = store.writeGroup(boneNode.value(), List.of(newCube), store.groupKeys(boneNode));
                newRoot = store.writeGroup(rootNode.value(), store.cubeKeys(rootNode), List.of(newBone));

                var oldModel = store.materializeGroups(List.of(oldRoot)).getFirst();
                var newModel = store.materializeGroups(List.of(newRoot)).getFirst();
                assertEquals(0, oldModel.children().getFirst().cubes().getFirst().origin().x());
                assertEquals(7, newModel.children().getFirst().cubes().getFirst().origin().x());
                var editedArchive = snapshot.apply(List.of(newModel));
                var rebuilt = YsmModelSnapshot.fromArchive(editedArchive);
                assertEquals(7, rebuilt.roots().getFirst().children().getFirst().cubes().getFirst().origin().x());
                assertArrayEquals(new byte[]{1, 2, 3, 4}, editedArchive.resource("textures/player.png"),
                        "editing geometry must preserve untouched resources");
                assertThrows(IllegalArgumentException.class, () -> snapshot.apply(List.of()));
            }

            try (var reopened = new YsmReferenceStore(world)) {
                var restoredSource = awaitLoad(reopened, snapshot.digest()).snapshot();
                assertEquals(snapshot.digest(), restoredSource.digest());
                assertEquals(snapshot.archive().digest(), restoredSource.archive().digest());
                assertEquals(0, reopened.materializeGroups(List.of(oldRoot)).getFirst()
                        .children().getFirst().cubes().getFirst().origin().x());
                assertArrayEquals(new byte[]{1, 2, 3, 4}, reopened.preview(oldCube, true).texture(),
                        "preview handles retain source context after reopening the world store");
                assertEquals(7, reopened.materializeGroups(List.of(newRoot)).getFirst()
                        .children().getFirst().cubes().getFirst().origin().x());
                assertNotEquals(oldCube, newCube);
                assertThrows(IllegalArgumentException.class, () -> reopened.readCube("f".repeat(64)));
                assertEquals(YsmReferenceStore.State.MISSING, reopened.querySnapshot("e".repeat(64)).state());
            }

            // The same object content gets another world-scoped key in a different save.
            Path otherWorld = Files.createTempDirectory("cmi-ysm-reference-other-");
            try (var other = new YsmReferenceStore(otherWorld)) {
                var otherCube = other.writeCube(YsmModelSnapshot.fromArchive(sampleArchive())
                        .roots().getFirst().children().getFirst().cubes().getFirst());
                assertNotEquals(oldCube, otherCube, "references must not resolve accidentally in another world");
                assertThrows(IllegalArgumentException.class, () -> other.readCube(oldCube));
                assertThrows(IllegalArgumentException.class, () -> other.preview(oldCube, true));
            } finally { deleteTree(otherWorld); }

            Path object = world.resolve("data/createmanaindustry/model/objects")
                    .resolve(newCube.substring(0, 2)).resolve(newCube + ".bin");
            byte[] corrupt = Files.readAllBytes(object);
            corrupt[corrupt.length - 1] ^= 0x01;
            Files.write(object, corrupt, StandardOpenOption.TRUNCATE_EXISTING);
            try (var reopened = new YsmReferenceStore(world)) {
                assertThrows(IllegalArgumentException.class, () -> reopened.readCube(newCube));
            }

            Path archive = world.resolve("data/createmanaindustry/model/archives")
                    .resolve(snapshot.digest().substring(0, 2)).resolve(snapshot.digest() + ".bin");
            byte[] corruptArchive = Files.readAllBytes(archive);
            corruptArchive[corruptArchive.length - 1] ^= 0x01;
            Files.write(archive, corruptArchive, StandardOpenOption.TRUNCATE_EXISTING);
            try (var reopened = new YsmReferenceStore(world)) {
                var result = awaitLoad(reopened, snapshot.digest());
                assertEquals(YsmReferenceStore.State.FAILED, result.state());
                assertTrue(result.reason().contains("SHA-256 verification failed"));
            }
        } finally { deleteTree(world); }
    }

    private static YsmReferenceStore.SnapshotResult awaitPersist(YsmReferenceStore store, YsmModelSnapshot snapshot) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            var result = store.persist(snapshot);
            if (result.state() == YsmReferenceStore.State.READY || result.state() == YsmReferenceStore.State.FAILED) return result;
            Thread.sleep(5);
        }
        fail("Timed out saving YSM reference snapshot");
        throw new AssertionError();
    }

    private static YsmReferenceStore.SnapshotResult awaitLoad(YsmReferenceStore store, String digest) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            var result = store.querySnapshot(digest);
            if (result.state() == YsmReferenceStore.State.READY || result.state() == YsmReferenceStore.State.FAILED
                    || result.state() == YsmReferenceStore.State.MISSING) return result;
            Thread.sleep(5);
        }
        fail("Timed out loading YSM reference snapshot");
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
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
