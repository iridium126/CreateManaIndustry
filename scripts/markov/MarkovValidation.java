import com.iridium126.createmanaindustry.worldgen.markov.MarkovModel;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;

/** Dependency-free correctness, determinism, concurrency, and throughput gate. */
public final class MarkovValidation {
    public static void main(String[] args) throws Exception {
        MarkovModel model;
        try (var input = Files.newInputStream(Path.of(args[0]))) { model = MarkovModel.load(input, 19, 19, 18); }
        Path directory = Path.of(args[1]);
        ByteBuffer reference = ByteBuffer.wrap(Files.readAllBytes(directory.resolve("reference.bin"))).order(ByteOrder.LITTLE_ENDIAN);
        int verified = 0;
        while (reference.hasRemaining()) {
            int seed = reference.getInt();
            byte[] expected = new byte[19 * 19 * 18]; reference.get(expected);
            byte[] actual = model.generate(seed, 1000);
            int mismatch = Arrays.mismatch(expected, actual);
            if (mismatch >= 0) throw new AssertionError("Seed " + seed + " voxel " + mismatch + ": expected " + expected[mismatch] + " actual " + actual[mismatch]);
            checkTree(actual, model.values());
            verified++;
        }
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Future<byte[]>> results = new ArrayList<>();
            for (int i = 0; i < 64; i++) { final int seed = i; results.add(executor.submit(() -> model.generate(seed, 1000))); }
            for (int i = 0; i < results.size(); i++) if (!Arrays.equals(results.get(i).get(), model.generate(i, 1000)))
                throw new AssertionError("Concurrent nondeterminism");
        }
        boolean limited = false;
        try { model.generate(1, 1); } catch (IllegalStateException expected) { limited = true; }
        if (!limited) throw new AssertionError("Step budget not enforced");
        rejects("<sequence values='BX' origin='True' symmetry='()'><wfc/></sequence>");
        rejects("<sequence values='BX' symmetry='()'><prl in='B' out='X' typo='1'/></sequence>");
        rejects("<!DOCTYPE x [<!ENTITY a SYSTEM 'file:///unused'>]><sequence values='BX' symmetry='()'/>");
        for (int i = 0; i < 1000; i++) model.generate(i, 1000);
        double[] times = new double[5]; long checksum = 0;
        for (int trial = 0; trial < times.length; trial++) {
            long start = System.nanoTime();
            for (int i = 0; i < 1000; i++) checksum += model.generate(i, 1000)[9 + 9 * 19];
            times[trial] = (System.nanoTime() - start) / 1e6 / 1000;
        }
        Arrays.sort(times);
        String report = "{\"runtime\":\"" + System.getProperty("java.runtime.version") + "\",\"verified_seeds\":" + verified
            + ",\"samples\":1000,\"warmup\":1000,\"trials\":5,\"median_ms\":" + times[2]
            + ",\"trials_ms\":" + Arrays.toString(times) + ",\"checksum\":" + checksum + "}";
        Files.writeString(directory.resolve("java-performance.json"), report);
        System.out.println(report);
        String original = Files.readString(directory.resolve("reference-performance.json"));
        var matcher = java.util.regex.Pattern.compile("\"median_ms\"\\s*:\\s*([0-9.Ee+-]+)").matcher(original);
        if (!matcher.find()) throw new AssertionError("Missing reference timing");
        double baseline = Double.parseDouble(matcher.group(1));
        System.out.printf(Locale.ROOT, "Upstream / Java throughput ratio: %.2fx%n", baseline / times[2]);
        if (times[2] > baseline) throw new AssertionError("Performance regression vs upstream");
    }

    private static void rejects(String xml) {
        try {
            MarkovModel.load(new ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)), 19, 19, 18);
            throw new AssertionError("Invalid XML accepted");
        } catch (IllegalArgumentException expected) { }
    }

    private static void checkTree(byte[] state, String values) {
        int count = 0, min = 18, max = -1, root = -1;
        for (int i = 0; i < state.length; i++) if (state[i] != 0) {
            char symbol = values.charAt(state[i]); int x = i % 19, y = i / 19 % 19, z = i / 361;
            if ("NDGEg".indexOf(symbol) < 0) throw new AssertionError("Transient symbol: " + symbol);
            if (x == 0 || x == 18 || y == 0 || y == 18 || z == 17) throw new AssertionError("Clipped tree");
            if ("GEg".indexOf(symbol) >= 0 && z < 3) throw new AssertionError("Leaves below clearance");
            min = Math.min(min, z); max = Math.max(max, z); root = i; count++;
        }
        if (min != 0 || max + 1 < 6 || max + 1 > 15) throw new AssertionError("Tree height/grounding");
        boolean[] seen = new boolean[state.length]; int[] queue = new int[state.length]; int end = 1;
        queue[0] = root; seen[root] = true;
        for (int start = 0; start < end; start++) {
            int at = queue[start];
            for (int delta : new int[] {-1,1,-19,19,-361,361}) {
                int next = at + delta;
                if (next >= 0 && next < state.length && state[next] != 0 && !seen[next]) {
                    seen[next] = true; queue[end++] = next;
                }
            }
        }
        if (end != count) throw new AssertionError("Disconnected tree");
    }
}
