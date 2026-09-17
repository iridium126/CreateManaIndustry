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
    @Test void completeBotanicalSeed137PreservesStructuralGrammar() throws Exception {
        Map<String, String> hashes = Map.of(
            "base", "99386e637128db3c5ae0c5a9ed42b3281dcad036b517421c89ceb0b7f86f147f",
            "stage1", "3aec8b4c89f757b6405ec6ecd109455fd7693fd52a8d5bfe6ccd485c9e89a36f",
            "stage2", "454974f89cdc48381f851f43ec0016d331fc5f087410d219814ada47a96999f7");
        // Independently obtained by running the MarkovJunior interpreter with the botanical reference extension.
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
            for (int z=0; z<volume.z; z++)
                assertNotEquals(0, volume.get(324,324,z), "continuous central leader at " + z);
        }
    }

    @Test void botanicalFieldsAreHollowVariedAndDeterministic() {
        var first = new RedwoodBotany(72, 72, 96, 137);
        var repeat = new RedwoodBotany(72, 72, 96, 137);
        var different = new RedwoodBotany(72, 72, 96, -1);
        int occupied = 0, changed = 0, stems = 0, leaves = 0, flowers = 0;
        int[] needleMaterials = new int[EpicRedwoodModel.VALUES.length()];
        for (int z=12; z<84; z++) for (int y=12; y<60; y++) for (int x=12; x<60; x++) {
            float wx=x+.5f, wy=y+.5f, wz=z+.5f;
            float tone=(x%9)/8f;
            byte needle=first.needle(wx,wy,wz,tone), vine=first.vine(wx,wy,wz);
            assertEquals(needle, repeat.needle(wx,wy,wz,tone));
            assertEquals(vine, repeat.vine(wx,wy,wz));
            needleMaterials[needle]++;
            if (needle!=0) occupied++;
            if (needle!=different.needle(wx,wy,wz,tone)) changed++;
            if (vine==7 || vine==11) stems++;
            if (vine==8 || vine==12) leaves++;
            if (vine==13) flowers++;
        }
        int samples=72*48*48;
        assertTrue(occupied>samples*.08 && occupied<samples*.4, "open needle sprays");
        assertTrue(changed>samples*.05, "seed changes shoot arrangement");
        for (int material : new int[]{4,5,6,10,14}) assertTrue(needleMaterials[material]>0);
        assertTrue(stems>leaves && leaves>flowers && flowers>0, "woody vines, sparse flowering nodes");
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
