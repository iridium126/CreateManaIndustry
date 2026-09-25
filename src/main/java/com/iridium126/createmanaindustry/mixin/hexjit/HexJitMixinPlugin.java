package com.iridium126.createmanaindustry.mixin.hexjit;

import com.iridium126.createmanaindustry.compat.hexcasting.jit.JitCompatibility;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.*;
import net.neoforged.fml.loading.FMLLoader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.*;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.ext.*;

/** Exact upstream bytecode gate plus a final, fail-closed foreign-Mixin check. */
public final class HexJitMixinPlugin implements IMixinConfigPlugin {
    private static final String OWN = "com.iridium126.createmanaindustry.mixin.hexjit.";
    private static final String ROOT = "at/petrak/hexcasting/";
    private static final Map<String, String> HASHES = Map.of(
            ROOT + "api/casting/arithmetic/engine/ArithmeticEngine", "6efec13888ffe53b9775a0374d1e337cd7f41e9d483070ca903e315288795e05",
            ROOT + "api/casting/arithmetic/engine/ArithmeticEngine$OpCandidates", "1c599f3b7122543adb70396eedd2af74a68f166fbe78181fc649497e6858a587",
            ROOT + "api/casting/iota/PatternIota", "86bffa9188bc1ff836409d9ed5b2e3806f2efe309f658e3374595fe7f274a69a",
            ROOT + "api/casting/eval/vm/CastingVM", "bf614cb94518113138c1e876566ff63a0b7d257e13151dad81d270e7cea2ab99",
            ROOT + "api/casting/eval/vm/FrameEvaluate", "302d66e09e6a6a3495333458ceeca3f33f2acc00dd25f3a0235abe0b742bb174",
            ROOT + "api/casting/eval/CastingEnvironment", "8df7b477388e046409bb59c9adbdd4beb6b9a55aa40518a352abfaa65c6d8909",
            ROOT + "common/casting/PatternRegistryManifest", "67ac1443a874c89e7c75657965110456ea08180fc1837a25f5ca073666fc7ac3");
    private static final Map<String, Set<String>> EXECUTION_PATHS = Map.of(
            ROOT + "api/casting/arithmetic/engine/ArithmeticEngine", Set.of("run"),
            ROOT + "api/casting/iota/PatternIota", Set.of("lookupAndOperate"),
            ROOT + "api/casting/eval/vm/CastingVM", Set.of("queueExecuteAndWrapIotas"),
            ROOT + "api/casting/eval/CastingEnvironment", Set.of("postExecution"),
            ROOT + "common/casting/PatternRegistryManifest", Set.of("processRegistry"));
    private final Set<String> pristine = new HashSet<>();

    @Override public void onLoad(String mixinPackage) {
        try {
            Object transformer = MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
            if (transformer instanceof IMixinTransformer mixin && mixin.getExtensions() instanceof Extensions extensions) {
                extensions.add(new FinalVerifier());
                JitCompatibility.verifierInstalled();
            } else JitCompatibility.disable("final transformer verifier unavailable");
        } catch (RuntimeException | LinkageError error) {
            JitCompatibility.disable("cannot install final transformer verifier: " + error.getClass().getSimpleName());
        }
    }

    @Override public boolean shouldApplyMixin(String target, String mixin) {
        var file = FMLLoader.getLoadingModList().getModFileById("hexcasting");
        if (file == null) return false;
        boolean version = file.getMods().stream().anyMatch(mod -> mod.getModId().equals("hexcasting")
                && mod.getVersion().toString().equals("0.12.0-devel-pre-53"));
        if (!version) { JitCompatibility.disable("unsupported Hexcasting version"); return false; }
        // Validate the actual jar, not only a version string. Do not install signature-sensitive hooks on an unknown ABI.
        String internal = target.replace('.', '/');
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(internal + ".class")) {
            if (stream == null || !sha(stream.readAllBytes()).equals(HASHES.get(internal))) {
                JitCompatibility.disable("upstream bytecode mismatch: " + target);
                return false;
            }
            return true;
        } catch (Exception error) {
            JitCompatibility.disable("cannot verify " + target);
            return false;
        }
    }

    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {
        if (!pristine.add(node.name)) return;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(node.name + ".class")) {
            if (stream == null) throw new IllegalStateException("missing upstream class");
            ClassNode original = new ClassNode();
            new ClassReader(stream).accept(original, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            for (MethodNode method : original.methods) {
                if (!EXECUTION_PATHS.getOrDefault(node.name, Set.of()).contains(method.name)) continue;
                MethodNode current = node.methods.stream().filter(m -> m.name.equals(method.name) && m.desc.equals(method.desc))
                        .findFirst().orElseThrow();
                byte[] expected = instructions(method);
                byte[] actual = instructions(current);
                if (!Arrays.equals(expected, actual)) {
                    int mismatch = firstDifference(expected, actual);
                    JitCompatibility.disable("transformed execution method: " + target + "." + method.name
                            + " at fingerprint byte " + mismatch + " (original=" + excerpt(expected, mismatch)
                            + ", transformed=" + excerpt(actual, mismatch) + ")");
                }
            }
        } catch (Exception error) { JitCompatibility.disable("cannot compare transformed class: " + target); }
    }

    /** Compare executable instructions without passing the live target labels to ASM's ClassWriter. */
    public static byte[] instructions(MethodNode method) {
        Set<LabelNode> controlFlowLabels = Collections.newSetFromMap(new IdentityHashMap<>());
        for (TryCatchBlockNode block : method.tryCatchBlocks) {
            controlFlowLabels.add(block.start);
            controlFlowLabels.add(block.end);
            controlFlowLabels.add(block.handler);
        }
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof JumpInsnNode n) controlFlowLabels.add(n.label);
            else if (insn instanceof TableSwitchInsnNode n) {
                controlFlowLabels.add(n.dflt); controlFlowLabels.addAll(n.labels);
            } else if (insn instanceof LookupSwitchInsnNode n) {
                controlFlowLabels.add(n.dflt); controlFlowLabels.addAll(n.labels);
            }
        }
        IdentityHashMap<LabelNode, Integer> labels = new IdentityHashMap<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof LabelNode label && controlFlowLabels.contains(label)) labels.put(label, labels.size());
        }
        StringBuilder fingerprint = new StringBuilder();
        for (TryCatchBlockNode block : method.tryCatchBlocks) {
            fingerprint.append("try:").append(label(labels, block.start)).append(':')
                    .append(label(labels, block.end)).append(':').append(label(labels, block.handler))
                    .append(':').append(block.type).append(';');
        }
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof FrameNode || insn instanceof LineNumberNode
                    || insn instanceof LabelNode label && !labels.containsKey(label)) continue;
            fingerprint.append(insn.getOpcode()).append(':');
            if (insn instanceof IntInsnNode n) fingerprint.append(n.operand);
            else if (insn instanceof VarInsnNode n) fingerprint.append(n.var);
            else if (insn instanceof TypeInsnNode n) fingerprint.append(n.desc);
            else if (insn instanceof FieldInsnNode n) fingerprint.append(n.owner).append('.').append(n.name).append(n.desc);
            else if (insn instanceof MethodInsnNode n) fingerprint.append(n.owner).append('.').append(n.name).append(n.desc).append(':').append(n.itf);
            else if (insn instanceof InvokeDynamicInsnNode n) fingerprint.append(n.name).append(n.desc).append(n.bsm).append(Arrays.deepToString(n.bsmArgs));
            else if (insn instanceof JumpInsnNode n) fingerprint.append(label(labels, n.label));
            else if (insn instanceof LabelNode n && labels.containsKey(n)) fingerprint.append('L').append(label(labels, n));
            else if (insn instanceof LdcInsnNode n) fingerprint.append(constant(n.cst));
            else if (insn instanceof IincInsnNode n) fingerprint.append(n.var).append(':').append(n.incr);
            else if (insn instanceof TableSwitchInsnNode n) {
                fingerprint.append(n.min).append(':').append(n.max).append(':').append(label(labels, n.dflt));
                for (LabelNode target : n.labels) fingerprint.append(':').append(label(labels, target));
            } else if (insn instanceof LookupSwitchInsnNode n) {
                fingerprint.append(label(labels, n.dflt)).append(':').append(n.keys);
                for (LabelNode target : n.labels) fingerprint.append(':').append(label(labels, target));
            } else if (insn instanceof MultiANewArrayInsnNode n) fingerprint.append(n.desc).append(':').append(n.dims);
            fingerprint.append(';');
        }
        return fingerprint.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static int label(IdentityHashMap<LabelNode, Integer> labels, LabelNode node) {
        return labels.computeIfAbsent(node, ignored -> labels.size());
    }

    private static String constant(Object value) {
        if (value instanceof Type type) return "type:" + type.getDescriptor();
        if (value instanceof Handle handle) return "handle:" + handle;
        return String.valueOf(value);
    }

    private static int firstDifference(byte[] left, byte[] right) {
        int common = Math.min(left.length, right.length);
        for (int i = 0; i < common; i++) if (left[i] != right[i]) return i;
        return common;
    }

    private static String excerpt(byte[] bytes, int position) {
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        int start = Math.max(0, position - 48), end = Math.min(text.length(), position + 48);
        return text.substring(start, end).replace('\n', ' ');
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static final class FinalVerifier implements IExtension {
        @Override public boolean checkActive(MixinEnvironment environment) { return true; }
        @Override public void preApply(ITargetClassContext context) {}
        @Override public void postApply(ITargetClassContext context) {
            ClassNode node = context.getClassNode();
            if (!HASHES.containsKey(node.name)) return;
            for (MethodNode method : node.methods) {
                if (!EXECUTION_PATHS.getOrDefault(node.name, Set.of()).contains(method.name)) continue;
                if (foreign(method.visibleAnnotations) || foreign(method.invisibleAnnotations)) {
                    JitCompatibility.disable("foreign execution Mixin: " + node.name + "." + method.name);
                    return;
                }
            }
            JitCompatibility.verified(node.name);
        }
        private boolean foreign(List<AnnotationNode> annotations) {
            if (annotations == null) return false;
            for (AnnotationNode annotation : annotations) {
                if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;")) continue;
                if (annotation.values == null) return true;
                for (int i = 0; i < annotation.values.size(); i += 2)
                    if (annotation.values.get(i).equals("mixin"))
                        return !annotation.values.get(i + 1).toString().startsWith(OWN);
                return true;
            }
            return false;
        }
        @Override public void export(MixinEnvironment environment, String name, boolean force, ClassNode node) {}
    }

    @Override public String getRefMapperConfig() { return null; }
    @Override public List<String> getMixins() { return null; }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) {}
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
}
