package com.iridium126.createmanaindustry.bootstrap;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * Adapts Trickster's keybinding handlers to NeoForge before Connector scans them.
 */
public final class TricksterJarPatchService implements ITransformationService {
    private static final String LOG_PREFIX = "[Create Mana Industry] ";
    private static final String CONNECTOR_SERVICE = "connector_loader";
    private static final String TRICKSTER_ID = "trickster";
    private static final String SUPPORTED_TRICKSTER_VERSION = "2.0.0-beta.56";
    private static final String EXPECTED_MIXIN_CLASS_SHA256 =
            "47a8849c8056c8f7776d3aa83cd359247ebf1b640a2994dd8f230c8ab6af7769";
    private static final String TEMPLATE_CLASS =
            "com/iridium126/createmanaindustry/bootstrap/TricksterKeyBindingPatch";
    private static final String MIXIN_CLASS =
            "dev/enjarai/trickster/mixin/client/key/KeyBindingMixin";
    private static final String MIXIN_ENTRY = MIXIN_CLASS + ".class";
    private static final String WORLD_MIXIN_ENTRY =
            "dev/enjarai/trickster/mixin/chunk_pinning/ServerWorldMixin.class";
    private static final String EXPECTED_WORLD_MIXIN_SHA256 =
            "2ba126372f77660835ad7d363098c7ec01accb44eb9b536d9589d7d97a3e27c7";
    private static final String WORLD_PIN_TARGET = "method_39998(Lnet/minecraft/class_1923;)Z";
    private static final String PATCH_MARKER_ENTRY = "META-INF/cmi/trickster-keybinding-patch.txt";
    private static final byte[] PATCH_MARKER = (
            "patch=cmi-trickster-keybinding-beta56-v4\n"
                    + "tricksterVersion=" + SUPPORTED_TRICKSTER_VERSION + "\n"
                    + "sourceClassSha256=" + EXPECTED_MIXIN_CLASS_SHA256 + "\n"
                    + "handlers=native-neoforge-keymapping-lookup\n"
                    + "worldPinTarget=" + WORLD_PIN_TARGET + "\n"
    ).getBytes(StandardCharsets.UTF_8);
    private static final String APPLY_KEYBIND_CONTEXT_DESCRIPTOR =
            "(Ljava/util/Map;Ljava/lang/Object;"
                    + "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)Ljava/lang/Object;";
    private static final String SKIP_ADDING_CONTEXTUAL_KEYS_DESCRIPTOR =
            "(Ljava/util/Map;Ljava/lang/Object;Ljava/lang/Object;"
                    + "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)Ljava/lang/Object;";
    private static final List<MethodSignature> KEYBINDING_HANDLERS = List.of(
            new MethodSignature("applyKeybindContext", APPLY_KEYBIND_CONTEXT_DESCRIPTOR, 3),
            new MethodSignature("applyKeybindContext2", APPLY_KEYBIND_CONTEXT_DESCRIPTOR, 3),
            new MethodSignature("skipAddingContextualKeys", SKIP_ADDING_CONTEXTUAL_KEYS_DESCRIPTOR, 4)
    );
    @Override
    public String name() {
        return "cmi_trickster_patch";
    }

    @Override
    public void initialize(IEnvironment environment) {
        // The filesystem patch must run from onLoad, before Connector begins scanning mods.
    }

    @Override
    public void onLoad(IEnvironment environment, Set<String> otherServices) {
        if (!otherServices.contains(CONNECTOR_SERVICE)) {
            return;
        }

        Path gameDirectory = environment.getProperty(IEnvironment.Keys.GAMEDIR.get())
                .orElse(Path.of(".").toAbsolutePath().normalize());
        patchInstalledTrickster(gameDirectory.resolve("mods"));
    }

    @Override
    public List<? extends ITransformer<?>> transformers() {
        return List.of();
    }

    private static void patchInstalledTrickster(Path modsDirectory) {
        if (!Files.isDirectory(modsDirectory)) {
            log("INFO", "Connector is present, but no mods directory was found at " + modsDirectory + ".");
            return;
        }

        List<InstalledJar> tricksterJars = findTricksterJars(modsDirectory);
        if (tricksterJars.isEmpty()) {
            return;
        }
        if (tricksterJars.size() != 1) {
            log("WARNING", "Found " + tricksterJars.size()
                    + " Trickster jars; skipping the automatic patch to avoid changing an ambiguous installation.");
            return;
        }

        InstalledJar trickster = tricksterJars.getFirst();
        if (!SUPPORTED_TRICKSTER_VERSION.equals(trickster.version())) {
            log("WARNING", "Trickster " + trickster.version() + " is installed at " + trickster.path()
                    + ", but automatic patching supports only " + SUPPORTED_TRICKSTER_VERSION
                    + ". The jar was left unchanged.");
            return;
        }

        try {
            if (trickster.patchMarker() != null) {
                if (MessageDigest.isEqual(PATCH_MARKER, trickster.patchMarker())
                        && hasPatchedTricksterMixins(trickster.path())) {
                    log("INFO", "Trickster " + SUPPORTED_TRICKSTER_VERSION + " was already patched.");
                } else {
                    log("WARNING", "A patch marker or modified KeyBindingMixin was found in " + trickster.path()
                            + ", but it does not match this patch. The jar was left unchanged.");
                }
                return;
            }

            String actualClassSha256 = mixinClassSha256(trickster.path());
            if (!EXPECTED_MIXIN_CLASS_SHA256.equals(actualClassSha256)) {
                log("WARNING", "Trickster reports version " + SUPPORTED_TRICKSTER_VERSION
                        + ", but its KeyBindingMixin.class does not match the supported upstream class."
                        + " Expected SHA-256 " + EXPECTED_MIXIN_CLASS_SHA256 + ", found " + actualClassSha256
                        + ". The jar was left unchanged.");
                return;
            }

            patchJar(trickster.path());
        } catch (Exception exception) {
            log("WARNING", "Could not patch Trickster at " + trickster.path()
                    + ". Automatic patching did not complete; check for a .backup file before retrying.");
            exception.printStackTrace(System.err);
        }
    }

    private static List<InstalledJar> findTricksterJars(Path modsDirectory) {
        List<InstalledJar> result = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(modsDirectory)) {
            for (Path path : entries) {
                if (!Files.isRegularFile(path)
                        || !path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    continue;
                }
                inspectTricksterJar(path, result);
            }
        } catch (IOException exception) {
            log("WARNING", "Could not scan " + modsDirectory + " for Trickster jars.");
            exception.printStackTrace(System.err);
        }
        return result;
    }

    private static void inspectTricksterJar(Path path, List<InstalledJar> result) {
        try (JarFile jar = new JarFile(path.toFile(), false)) {
            JarEntry metadataEntry = jar.getJarEntry("fabric.mod.json");
            if (metadataEntry == null) {
                return;
            }

            byte[] metadata = readBounded(jar, metadataEntry, 1_048_576);
            String id = topLevelJsonString(metadata, "id");
            if (!TRICKSTER_ID.equals(id)) {
                return;
            }

            String version = topLevelJsonString(metadata, "version");
            JarEntry markerEntry = jar.getJarEntry(PATCH_MARKER_ENTRY);
            byte[] marker = markerEntry == null ? null : readBounded(jar, markerEntry, 4096);
            result.add(new InstalledJar(path, version, marker));
        } catch (IOException ignored) {
            // Ignore unrelated or malformed jars; Connector/FML will report those itself.
        }
    }

    private static String topLevelJsonString(byte[] jsonBytes, String wantedKey) {
        try {
            return new JsonObjectFieldReader(new String(jsonBytes, StandardCharsets.UTF_8))
                    .readStringField(wantedKey);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static void patchJar(Path jarPath) throws IOException {
        Path backupPath = jarPath.resolveSibling(jarPath.getFileName() + ".backup");
        FileTime originalModifiedTime = Files.getLastModifiedTime(jarPath);
        Path temporaryPath = Files.createTempFile(
                jarPath.getParent(), jarPath.getFileName().toString() + ".", ".cmi-patching-tmp");

        boolean replacedOriginal = false;
        try {
            try (JarFile input = new JarFile(jarPath.toFile(), false);
                 JarOutputStream output = new JarOutputStream(Files.newOutputStream(temporaryPath))) {
                Enumeration<JarEntry> entries = input.entries();
                boolean foundMixin = false;
                boolean foundWorldMixin = false;
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (isSignatureFile(name) || PATCH_MARKER_ENTRY.equals(name)) {
                        continue;
                    }

                    byte[] content;
                    if (MIXIN_ENTRY.equals(name)) {
                        if (foundMixin) {
                            throw new IOException("The jar contains duplicate " + MIXIN_ENTRY + " entries");
                        }
                        foundMixin = true;
                        content = patchMixinClass(readBounded(input, entry, 4 * 1024 * 1024));
                    } else if (WORLD_MIXIN_ENTRY.equals(name)) {
                        if (foundWorldMixin) {
                            throw new IOException("Duplicate " + WORLD_MIXIN_ENTRY);
                        }
                        foundWorldMixin = true;
                        content = patchWorldMixin(readBounded(input, entry, 4 * 1024 * 1024));
                    } else {
                        content = readBounded(input, entry, Integer.MAX_VALUE);
                    }

                    JarEntry outputEntry = new JarEntry(name);
                    if (entry.getTime() >= 0) {
                        outputEntry.setTime(entry.getTime());
                    }
                    if (entry.getComment() != null) {
                        outputEntry.setComment(entry.getComment());
                    }
                    output.putNextEntry(outputEntry);
                    if (content.length != 0) {
                        output.write(content);
                    }
                    output.closeEntry();
                }

                if (!foundMixin || !foundWorldMixin) {
                    throw new IOException("The jar is missing a required Trickster mixin");
                }

                JarEntry marker = new JarEntry(PATCH_MARKER_ENTRY);
                marker.setTime(originalModifiedTime.toMillis());
                output.putNextEntry(marker);
                output.write(PATCH_MARKER);
                output.closeEntry();
            }

            Files.setLastModifiedTime(temporaryPath, originalModifiedTime);
            verifyPatchedJar(temporaryPath);
            createOrVerifyBackup(jarPath, backupPath);
            moveReplacing(temporaryPath, jarPath);
            replacedOriginal = true;
            verifyPatchedJar(jarPath);
            log("INFO", "Patched Trickster " + SUPPORTED_TRICKSTER_VERSION + " before Connector scanned it."
                    + " The original jar is backed up as " + backupPath.getFileName() + ".");
        } catch (IOException exception) {
            if (replacedOriginal) {
                try {
                    Files.copy(backupPath, jarPath, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException rollbackException) {
                    exception.addSuppressed(rollbackException);
                }
            }
            throw exception;
        } finally {
            Files.deleteIfExists(temporaryPath);
        }
    }

    private static byte[] patchMixinClass(byte[] originalClass) throws IOException {
        String classSha256 = sha256(originalClass);
        if (!EXPECTED_MIXIN_CLASS_SHA256.equals(classSha256)) {
            throw new IOException("Unsupported " + MIXIN_ENTRY + " SHA-256: " + classSha256);
        }

        ClassReader reader = new ClassReader(originalClass);
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        reader.accept(classNode, 0);
        if (!MIXIN_CLASS.equals(classNode.name)) {
            throw new IOException("Unexpected class name in " + MIXIN_ENTRY + ": " + classNode.name);
        }

        ClassNode template = readPatchTemplate();
        for (MethodSignature signature : KEYBINDING_HANDLERS) {
            List<MethodNode> matches = classNode.methods.stream()
                    .filter(method -> method.name.equals(signature.name()) && method.desc.equals(signature.descriptor()))
                    .toList();
            if (matches.size() != 1
                    || (matches.getFirst().access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC))
                    != (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)
                    || matches.getFirst().parameters == null
                    || matches.getFirst().parameters.size() != signature.parameterCount()) {
                throw new IOException("Unexpected " + signature.name() + " method in " + MIXIN_ENTRY);
            }
            MethodNode replacement = template.methods.stream()
                    .filter(method -> method.name.equals(signature.name())).findFirst()
                    .orElseThrow(() -> new IOException("Patch template is missing " + signature.name()));
            classNode.methods.set(classNode.methods.indexOf(matches.getFirst()), replacement);
        }

        ClassWriter writer = new ClassWriter(reader, 0);
        classNode.accept(writer);
        byte[] patchedClass = writer.toByteArray();
        verifyPatchedMixinClass(patchedClass);
        return patchedClass;
    }

    private static ClassNode readPatchTemplate() throws IOException {
        ClassNode template = new ClassNode(Opcodes.ASM9);
        try (InputStream input = TricksterJarPatchService.class.getResourceAsStream("/" + TEMPLATE_CLASS + ".class")) {
            if (input == null) {
                throw new IOException("Missing keybinding patch template");
            }
            // The source jar is intermediary mapped. Connector subsequently maps
            // these descriptors and setDown calls along with Trickster's helper.
            new ClassReader(input).accept(new ClassRemapper(template, new Remapper() {
                @Override
                public String map(String name) {
                    return switch (name) {
                        case TEMPLATE_CLASS -> MIXIN_CLASS;
                        case "net/minecraft/client/KeyMapping" -> "net/minecraft/class_304";
                        case "com/mojang/blaze3d/platform/InputConstants$Key" -> "net/minecraft/class_3675$class_306";
                        default -> name;
                    };
                }

                @Override
                public String mapMethodName(String owner, String name, String descriptor) {
                    return owner.equals("net/minecraft/client/KeyMapping") && name.equals("setDown")
                            && descriptor.equals("(Z)V") ? "method_23481" : name;
                }
            }), 0);
        }
        return template;
    }

    private static byte[] patchWorldMixin(byte[] originalClass) throws IOException {
        if (!EXPECTED_WORLD_MIXIN_SHA256.equals(sha256(originalClass))) {
            throw new IOException("Unsupported " + WORLD_MIXIN_ENTRY + " SHA-256");
        }
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM9);
        reader.accept(node, 0);
        AnnotationNode inject = worldPinInjection(node);
        int methodIndex = inject.values.indexOf("method");
        if (methodIndex < 0 || !List.of("method_39998").equals(inject.values.get(methodIndex + 1))) {
            throw new IOException("Unexpected worldPinTick target");
        }
        // NeoForge adds a BlockPos overload with the same mapped name. A bare
        // name selects both overloads and gives the ChunkPos handler a bad ABI.
        inject.values.set(methodIndex + 1, new ArrayList<>(List.of(WORLD_PIN_TARGET)));
        ClassWriter writer = new ClassWriter(reader, 0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static AnnotationNode worldPinInjection(ClassNode node) throws IOException {
        for (MethodNode method : node.methods) {
            if (method.name.equals("worldPinTick") && method.visibleAnnotations != null) {
                for (AnnotationNode annotation : method.visibleAnnotations) {
                    if (annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) {
                        return annotation;
                    }
                }
            }
        }
        throw new IOException("Missing worldPinTick injection");
    }

    private static void verifyPatchedJar(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
            JarEntry metadata = jar.getJarEntry("fabric.mod.json");
            if (metadata == null
                    || !TRICKSTER_ID.equals(topLevelJsonString(readBounded(jar, metadata, 1_048_576), "id"))
                    || !SUPPORTED_TRICKSTER_VERSION.equals(
                    topLevelJsonString(readBounded(jar, metadata, 1_048_576), "version"))) {
                throw new IOException("Patched jar metadata did not verify");
            }

            JarEntry marker = jar.getJarEntry(PATCH_MARKER_ENTRY);
            if (marker == null || !MessageDigest.isEqual(PATCH_MARKER, readBounded(jar, marker, 4096))) {
                throw new IOException("Patched jar marker did not verify");
            }

            JarEntry mixin = jar.getJarEntry(MIXIN_ENTRY);
            if (mixin == null) {
                throw new IOException("Patched jar is missing " + MIXIN_ENTRY);
            }
            verifyPatchedMixinClass(readBounded(jar, mixin, 4 * 1024 * 1024));
            JarEntry worldMixin = jar.getJarEntry(WORLD_MIXIN_ENTRY);
            if (worldMixin == null) {
                throw new IOException("Missing " + WORLD_MIXIN_ENTRY);
            }
            ClassNode world = new ClassNode(Opcodes.ASM9);
            new ClassReader(readBounded(jar, worldMixin, 4 * 1024 * 1024)).accept(world, 0);
            AnnotationNode inject = worldPinInjection(world);
            int methodIndex = inject.values.indexOf("method");
            if (methodIndex < 0 || !List.of(WORLD_PIN_TARGET).equals(inject.values.get(methodIndex + 1))) {
                throw new IOException("Patched worldPinTick target did not verify");
            }
        }
    }

    private static boolean hasPatchedTricksterMixins(Path jarPath) {
        try {
            verifyPatchedJar(jarPath);
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private static void verifyPatchedMixinClass(byte[] classBytes) throws IOException {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        new ClassReader(classBytes).accept(classNode, ClassReader.SKIP_FRAMES);
        if (!MIXIN_CLASS.equals(classNode.name)) {
            throw new IOException("Unexpected class name in patched " + MIXIN_ENTRY + ": " + classNode.name);
        }
        ClassNode template = readPatchTemplate();
        for (MethodSignature signature : KEYBINDING_HANDLERS) {
            MethodNode expected = template.methods.stream().filter(method -> method.name.equals(signature.name()))
                    .findFirst().orElseThrow(() -> new IOException("Missing patch template handler"));
            List<MethodNode> matches = classNode.methods.stream()
                    .filter(method -> method.name.equals(signature.name()) && method.desc.equals(expected.desc))
                    .toList();
            if (matches.size() != 1
                    || (matches.getFirst().access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC))
                    != (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)
                    || matches.getFirst().visibleAnnotations == null
                    || !matches.getFirst().visibleAnnotations.stream().anyMatch(annotation ->
                    annotation.desc.equals("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;"))
                    || matches.getFirst().instructions.size() == 0) {
                throw new IOException("Patched class did not preserve the " + signature.name() + " handler");
            }
        }
    }

    private static void createOrVerifyBackup(Path jarPath, Path backupPath) throws IOException {
        if (Files.exists(backupPath)) {
            if (!EXPECTED_MIXIN_CLASS_SHA256.equals(mixinClassSha256(backupPath))) {
                throw new IOException("Refusing to overwrite an existing non-matching backup: " + backupPath);
            }
            return;
        }
        Files.copy(jarPath, backupPath, StandardCopyOption.COPY_ATTRIBUTES);
        if (!EXPECTED_MIXIN_CLASS_SHA256.equals(mixinClassSha256(backupPath))) {
            Files.deleteIfExists(backupPath);
            throw new IOException("The original jar backup did not match the verified class fingerprint");
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean isSignatureFile(String name) {
        String upperName = name.toUpperCase(Locale.ROOT);
        if (!upperName.startsWith("META-INF/")) {
            return false;
        }
        String fileName = upperName.substring("META-INF/".length());
        if (fileName.contains("/")) {
            return false;
        }
        return fileName.endsWith(".SF")
                || fileName.endsWith(".RSA")
                || fileName.endsWith(".DSA")
                || fileName.endsWith(".EC")
                || fileName.startsWith("SIG-");
    }

    private static byte[] readBounded(JarFile jar, JarEntry entry, int maximumBytes) throws IOException {
        if (entry.getSize() > maximumBytes) {
            throw new IOException("Entry exceeds size limit: " + entry.getName());
        }
        try (InputStream input = jar.getInputStream(entry);
             ByteArrayOutputStream output = new ByteArrayOutputStream(
                     entry.getSize() > 0 ? (int) Math.min(entry.getSize(), 8192) : 256)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if ((long) output.size() + read > maximumBytes) {
                    throw new IOException("Entry exceeds size limit: " + entry.getName());
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String mixinClassSha256(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
            JarEntry mixin = jar.getJarEntry(MIXIN_ENTRY);
            if (mixin == null) {
                throw new IOException("The jar does not contain " + MIXIN_ENTRY);
            }
            return sha256(readBounded(jar, mixin, 4 * 1024 * 1024));
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static void log(String level, String message) {
        System.err.println(LOG_PREFIX + level + ": " + message);
    }

    private record InstalledJar(Path path, String version, byte[] patchMarker) {}

    private record MethodSignature(String name, String descriptor, int parameterCount) {}

    private static final class JsonObjectFieldReader {
        private final String json;
        private int index;

        private JsonObjectFieldReader(String json) {
            this.json = json;
        }

        private String readStringField(String wantedKey) {
            skipWhitespace();
            expect('{');
            skipWhitespace();
            if (consume('}')) {
                return null;
            }

            while (true) {
                String key = readString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                if (wantedKey.equals(key)) {
                    return peek('"') ? readString() : null;
                }
                skipValue();
                skipWhitespace();
                if (consume('}')) {
                    return null;
                }
                expect(',');
                skipWhitespace();
            }
        }

        private void skipValue() {
            skipWhitespace();
            if (peek('"')) {
                readString();
                return;
            }
            if (consume('{')) {
                skipWhitespace();
                if (consume('}')) {
                    return;
                }
                while (true) {
                    readString();
                    skipWhitespace();
                    expect(':');
                    skipValue();
                    skipWhitespace();
                    if (consume('}')) {
                        return;
                    }
                    expect(',');
                    skipWhitespace();
                }
            }
            if (consume('[')) {
                skipWhitespace();
                if (consume(']')) {
                    return;
                }
                while (true) {
                    skipValue();
                    skipWhitespace();
                    if (consume(']')) {
                        return;
                    }
                    expect(',');
                }
            }

            int valueStart = index;
            while (index < json.length() && ",]} \t\r\n".indexOf(json.charAt(index)) < 0) {
                index++;
            }
            if (index == valueStart) {
                throw new IllegalArgumentException("Missing JSON value");
            }
        }

        private String readString() {
            skipWhitespace();
            expect('"');
            StringBuilder value = new StringBuilder();
            while (index < json.length()) {
                char current = json.charAt(index++);
                if (current == '"') {
                    return value.toString();
                }
                if (current != '\\') {
                    value.append(current);
                    continue;
                }
                if (index >= json.length()) {
                    throw new IllegalArgumentException("Incomplete JSON escape");
                }
                char escaped = json.charAt(index++);
                switch (escaped) {
                    case '"', '\\', '/' -> value.append(escaped);
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'u' -> {
                        if (index + 4 > json.length()) {
                            throw new IllegalArgumentException("Incomplete unicode escape");
                        }
                        try {
                            value.append((char) Integer.parseInt(json.substring(index, index + 4), 16));
                        } catch (NumberFormatException exception) {
                            throw new IllegalArgumentException("Invalid unicode escape", exception);
                        }
                        index += 4;
                    }
                    default -> throw new IllegalArgumentException("Invalid JSON escape");
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }

        private void skipWhitespace() {
            while (index < json.length() && Character.isWhitespace(json.charAt(index))) {
                index++;
            }
        }

        private boolean peek(char expected) {
            return index < json.length() && json.charAt(index) == expected;
        }

        private boolean consume(char expected) {
            if (!peek(expected)) {
                return false;
            }
            index++;
            return true;
        }

        private void expect(char expected) {
            if (!consume(expected)) {
                throw new IllegalArgumentException("Expected '" + expected + "' in JSON");
            }
        }
    }
}
