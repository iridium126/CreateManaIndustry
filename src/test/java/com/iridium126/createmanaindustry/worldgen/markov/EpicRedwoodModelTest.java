package com.iridium126.createmanaindustry.worldgen.markov;

import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EpicRedwoodModelTest {
    @Test void completeUpstreamSeed137IncludingBothGlobalConnectivityPasses() throws Exception {
        Map<String, String> hashes = Map.of(
            "base", "99386e637128db3c5ae0c5a9ed42b3281dcad036b517421c89ceb0b7f86f147f",
            "stage1", "c78d455fd55668c421f8b883e55633ca79ba0e1cd06011038831faa2aab557d4",
            "stage2", "43a2fb0c68919d0c142110ad73ca2e58eed532af854ccc498f2fe283477d7e69");
        // Independently obtained by running the unchanged reference C# interpreter.
        try (var xml = getClass().getResourceAsStream("/data/createmanaindustry/markov/epic_redwood_3072.xml")) {
            assertNotNull(xml);
            var volume = new EpicRedwoodModel(xml).generate(137, (stage, grid) -> {
                try {
                    var hash = MessageDigest.getInstance("SHA-256");
                    grid.writeTo(new DigestOutputStream(OutputStream.nullOutputStream(), hash));
                    assertEquals(hashes.get(stage), HexFormat.of().formatHex(hash.digest()), stage);
                } catch (Exception e) { throw new AssertionError(e); }
            });
            assertEquals(648, volume.x); assertEquals(648, volume.y); assertEquals(3072, volume.z);
            assertTrue(volume.allocatedBytes() < 128L * 1024 * 1024);
            assertFalse(volume.intersects(-32, 0, 0, 16));
            assertFalse(volume.intersects(0, 0, 3072, 32));
            assertEquals(0, volume.get(-1, 0, 0));
        }
    }

    @Test void sparseFloodMatchesIndependentDenseFloodAcrossPageBoundaries() {
        int x = 35, y = 19, z = 33, plane = x * y;
        byte[] dense = new byte[x * y * z];
        var random = new Random(123);
        for (int i = 0; i < dense.length; i++) dense[i] = random.nextDouble() < .35 ? (byte)(1 + random.nextInt(9)) : 0;
        var sparse = RedwoodVolume.from(dense, x, y, z);
        boolean[] visited = new boolean[dense.length];
        var queue = new ArrayDeque<Integer>();
        for (int i = 0; i < dense.length; i++) if (dense[i] != 0) { queue.add(i); visited[i] = true; break; }
        while (!queue.isEmpty()) {
            int i = queue.remove(), a = i % x, b = i / x % y, c = i / plane;
            int[] neighbors = {a > 0 ? i - 1 : -1, a + 1 < x ? i + 1 : -1,
                b > 0 ? i - x : -1, b + 1 < y ? i + x : -1, c > 0 ? i - plane : -1, c + 1 < z ? i + plane : -1};
            for (int next : neighbors) if (next >= 0 && dense[next] != 0 && !visited[next]) { visited[next] = true; queue.add(next); }
        }
        sparse.retainRoot();
        for (int i = 0; i < dense.length; i++)
            assertEquals(visited[i] ? dense[i] : 0, sparse.get(i % x, i / x % y, i / plane), "index=" + i);
    }
}
