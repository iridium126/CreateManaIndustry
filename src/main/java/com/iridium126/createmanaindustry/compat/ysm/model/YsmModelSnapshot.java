package com.iridium126.createmanaindustry.compat.ysm.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Group;
import com.iridium126.createmanaindustry.compat.ysm.internal.security.YsmFileFormat;
import com.iridium126.createmanaindustry.compat.ysm.internal.security.YsmLegacyArchiveParser;

/** One source snapshot shared by all its geometry iotas. Loading is worker-thread work. */
public final class YsmModelSnapshot {
    private final String digest;
    private final List<Group> roots;
    private final YsmResourceArchive resources;
    private final Set<String> parts;
    private final long weight;
    private YsmModelSnapshot(String digest, List<Group> roots, YsmResourceArchive resources) {
        this.digest = digest; this.roots = List.copyOf(roots); this.resources = resources;
        var parts = new HashSet<String>(); long weight = resources.byteSize();
        for (Group root : roots) { parts.add(root.root().part()); weight += 4L * YsmGeometryIO.estimateGroupSize(root); }
        this.parts = Set.copyOf(parts); this.weight = weight;
        if (!digest.equals(YsmGeometry.validateRoots(roots, parts))) throw new IllegalArgumentException("Source snapshot mismatch");
        YsmPlaintextModel.validateReferences(resources);
    }
    public static YsmModelSnapshot load(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            var model = new YsmPlaintextModel(YsmResourceArchive.readDirectory(path));
            return new YsmModelSnapshot(model.digest(), model.roots(), model.source());
        }
        byte[] bytes = Files.readAllBytes(path);
        int cryptoVersion;
        try { cryptoVersion = YsmFileFormat.cryptoVersion(bytes); }
        catch (IllegalArgumentException failure) { throw new IOException("Invalid YSM file header", failure); }
        if (cryptoVersion < 3) {
            try {
                var archive = new YsmResourceArchive(YsmLegacyArchiveParser.decrypt(bytes));
                var model = new YsmPlaintextModel(archive);
                return new YsmModelSnapshot(model.digest(), model.roots(), model.source());
            } catch (Exception failure) {
                throw new IOException("Invalid legacy YSM resource archive", failure);
            }
        }
        var model = YsmCompiledModel.decode(bytes);
        return new YsmModelSnapshot(model.digest(), model.roots(), new YsmResourceArchive(YsmCompiledExporter.build(model, model.roots())));
    }
    public String digest() { return digest; }
    public List<Group> roots() { return roots; }
    public long weight() { return weight; }
    public String defaultTexture() { return resources.defaultTexture(); }
    public YsmResourceArchive archive() { return resources; }
    public YsmResourceArchive apply(List<Group> edited) {
        if (!digest.equals(YsmGeometry.validateRoots(edited, parts))) throw new IllegalArgumentException("Source snapshot mismatch");
        var files = new LinkedHashMap<String, byte[]>();
        for (String path : resources.paths()) files.put(path, resources.resource(path));
        for (Group root : edited) files.put(root.root().part(), YsmGeometryJson.write(root).toString().getBytes(StandardCharsets.UTF_8));
        var result = new YsmResourceArchive(files);
        YsmPlaintextModel.validateReferences(result);
        return result;
    }
}
