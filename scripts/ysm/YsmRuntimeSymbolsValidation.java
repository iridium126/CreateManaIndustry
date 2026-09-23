package com.iridium126.createmanaindustry.compat.ysm;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

/** Local fixture validation. Never prints or writes proprietary runtime names. */
public final class YsmRuntimeSymbolsValidation {
    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]);
        var snapshot = YsmRuntimeSymbols.inspect(jar, YsmRuntimeSymbols.VERSION);
        if (snapshot.playerState() == null || snapshot.serverReload() == null
                || snapshot.nativeGeometryClass() == null) throw new AssertionError("Missing roles");
        reject(() -> YsmRuntimeSymbols.inspect(jar, "2.6.6"));
        reject(() -> YsmRuntimeSymbols.resolve(List.of()));
        List<ClassNode> classes = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.getName().startsWith("com/elfmcys/yesstevemodel/")
                        || !entry.getName().endsWith(".class")) continue;
                ClassNode node = new ClassNode();
                try (var stream = zip.getInputStream(entry)) {
                    new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
                classes.add(node);
            }
        }
        String stateOwner = snapshot.playerState().owner();
        classes.add(classes.stream().filter(c -> c.name.equals(stateOwner)).findFirst().orElseThrow());
        reject(() -> YsmRuntimeSymbols.resolve(classes));
        System.out.println("PASS: exact fixture roles, unsupported version, missing and ambiguous candidates");
    }

    private static void reject(Checked action) throws Exception {
        try { action.run(); }
        catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError("Unsafe candidate accepted");
    }
    private interface Checked { void run() throws Exception; }
}
