package com.iridium126.createmanaindustry.compat.ysm.validation;

import java.nio.file.Files;
import java.nio.file.Path;
import com.iridium126.createmanaindustry.compat.ysm.model.*;

public final class YsmCompiledModelValidation {
    public static void main(String[] args) throws Exception {
        byte[] input = Files.readAllBytes(Path.of(args[0]));
        var model = YsmCompiledModel.decode(input);
        int cubes = 0;
        for (var root : model.roots()) {
            byte[] encoded = YsmGeometryIO.encodeGroup(root);
            if (!root.equals(YsmGeometryIO.decodeGroup(encoded))) throw new AssertionError("Snapshot root round trip failed");
            cubes += count(root);
        }
        byte[] damaged = input.clone(); damaged[0] ^= 1;
        reject(() -> YsmCompiledModel.decode(damaged));
        reject(() -> YsmCompiledModel.decode(new byte[0]));
        byte[] sourceCopy = model.sourceBytes(); sourceCopy[0] ^= 1;
        if (!java.util.Arrays.equals(model.sourceBytes(), input)) throw new AssertionError("Snapshot source is mutable");
        var exported = YsmCompiledExporter.build(model, model.roots());
        for (var root : model.roots()) {
            String json = new String(exported.get(root.root().part()), java.nio.charset.StandardCharsets.UTF_8);
            var parsed = YsmGeometryReader.read(model.digest(), root.root().part(), json);
            var written = YsmGeometryJson.write(parsed);
            if (!YsmJson.resource(json).equals(written)) throw new AssertionError("Plaintext geometry lost data on read/write");
        }
        reject(() -> YsmJson.object("{\"duplicate\":1,\"duplicate\":2}"));
        reject(() -> YsmJson.object("{\"a\":" + "[".repeat(70) + "0" + "]".repeat(70) + "}"));
        reject(() -> YsmJson.object("{\"a\":NaN}"));
        var archive = new YsmResourceArchive(exported);
        var plaintext = new YsmPlaintextModel(archive);
        var plainEdited = plaintext.apply(plaintext.roots());
        for (String path : archive.paths()) if (plaintext.roots().stream().noneMatch(g -> g.root().part().equals(path))) {
            if (!java.util.Arrays.equals(archive.resource(path), plainEdited.resource(path)))
                throw new AssertionError("Plaintext round trip changed an unedited resource");
        }
        var decoded = YsmResourceArchive.decode(archive.encode());
        if (!archive.digest().equals(decoded.digest()) || !archive.paths().equals(decoded.paths()))
            throw new AssertionError("Archive digest round trip failed");
        for (String path : archive.paths()) {
            if (!java.util.Arrays.equals(archive.resource(path), decoded.resource(path))) throw new AssertionError("Archive resource changed");
        }
        byte[] config = archive.resource("ysm.json"); config[0] ^= 1;
        if (!java.util.Arrays.equals(archive.resource("ysm.json"), exported.get("ysm.json"))) throw new AssertionError("Archive exposes mutable bytes");
        reject(() -> new YsmResourceArchive(java.util.Map.of("../escaped", new byte[0])));
        reject(() -> new YsmResourceArchive(java.util.Map.of("A", new byte[0], "a", new byte[0])));
        reject(() -> new YsmResourceArchive(java.util.Map.of("a", new byte[0], "a/b", new byte[0])));
        reject(() -> new YsmResourceArchive(java.util.Map.of("NUL.txt", new byte[0])));
        reject(() -> new YsmResourceArchive(java.util.Map.of("a\nb", new byte[0])));
        byte[] truncated = java.util.Arrays.copyOf(archive.encode(), archive.byteSize() - 1);
        reject(() -> YsmResourceArchive.decode(truncated));
        byte[] extra = java.util.Arrays.copyOf(archive.encode(), archive.byteSize() + 1);
        reject(() -> YsmResourceArchive.decode(extra));
        Path directory = Files.createTempDirectory(Path.of(args[0]).toAbsolutePath().getParent(), "export-validation-");
        Path first = archive.export(directory), second = archive.export(directory);
        if (!YsmResourceArchive.readDirectory(first).digest().equals(archive.digest())) throw new AssertionError("Directory reread changed resource digest");
        try (var store = new com.iridium126.createmanaindustry.compat.ysm.YsmSnapshotStore()) {
            store.prewarm("sample", Path.of(args[0]));
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            com.iridium126.createmanaindustry.compat.ysm.YsmSnapshotStore.Result result;
            do {
                result = store.query("sample");
                if (result.status() != com.iridium126.createmanaindustry.compat.ysm.YsmSnapshotStore.Status.LOADING) break;
                Thread.sleep(10);
            } while (System.nanoTime() < deadline);
            if (result.status() != com.iridium126.createmanaindustry.compat.ysm.YsmSnapshotStore.Status.READY)
                throw new AssertionError("Async snapshot failed: " + result.reason());
            if (!store.require(model.digest()).roots().equals(model.roots())) throw new AssertionError("Cache returned wrong source");
            reject(() -> store.require("0".repeat(64)));
            var applied = result.snapshot().apply(result.snapshot().roots());
            if (!applied.paths().equals(archive.paths())) throw new AssertionError("Snapshot application lost resources");
            try (var prepared = new com.iridium126.createmanaindustry.compat.ysm.YsmPreparedCache()) {
                long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
                com.iridium126.createmanaindustry.compat.ysm.YsmPreparedCache.Result modelResult;
                do {
                    modelResult = prepared.getOrPrepare(result.snapshot(), result.snapshot().roots());
                    if (modelResult.state() != com.iridium126.createmanaindustry.compat.ysm.YsmPreparedCache.State.LOADING) break;
                    Thread.sleep(10);
                } while (System.nanoTime() < until);
                if (modelResult.state() != com.iridium126.createmanaindustry.compat.ysm.YsmPreparedCache.State.READY
                        || !modelResult.archive().digest().equals(applied.digest()))
                    throw new AssertionError("Prepared model differs from direct application: " + modelResult.reason());
            }
            store.invalidate("sample");
            if (store.query("sample").status() != com.iridium126.createmanaindustry.compat.ysm.YsmSnapshotStore.Status.MISSING)
                throw new AssertionError("Invalidated lookup remained ready");
        }
        if (first.equals(second)) throw new AssertionError("Export overwrote previous output");
        for (String path : archive.paths()) {
            if (!java.util.Arrays.equals(Files.readAllBytes(first.resolve(path)), archive.resource(path)))
                throw new AssertionError("Exported bytes changed");
        }
        try (var paths = Files.list(first.getParent())) {
            if (paths.anyMatch(p -> p.getFileName().toString().startsWith(".pending-")))
                throw new AssertionError("Export left a temporary directory");
        }
        System.out.println("PASS: " + archive.paths().size() + " exported resources, immutable digest, archive validation and non-overwriting directory publication");
        System.out.println("PASS: compiled format " + model.formatVersion() + ", " + model.roots().size()
                + " roots, " + cubes + " cubes; verified vertices/normals/UV, persistence and corruption rejection");
    }
    private static int count(YsmGeometry.Group group) {
        return group.cubes().size() + group.children().stream().mapToInt(YsmCompiledModelValidation::count).sum();
    }
    private static void reject(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid compiled model accepted");
    }
}
