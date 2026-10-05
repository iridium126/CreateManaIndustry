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
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String VEC3 = "net/minecraft/world/phys/Vec3";
    private static final String PARTICLE_SIDE_EFFECT = ROOT
            + "api/casting/eval/sideeffects/OperatorSideEffect$Particles";
    private static final String IOTA_TYPE = ROOT + "api/casting/iota/IotaType";
    private static final String IOTA = ROOT + "api/casting/iota/Iota";
    private static final String CASTING_VM = ROOT + "api/casting/eval/vm/CastingVM";
    private static final String SPELL_ACTION = ROOT + "api/casting/castables/SpellAction";
    private static final String ADD_MOTION = ROOT + "common/casting/actions/spells/OpAddMotion";
    private static final String NBT_COMPOUND = "net/minecraft/nbt/CompoundTag";
    private static final String NUMBER_LITERAL = ROOT + "common/casting/actions/math/SpecialHandlerNumberLiteral";
    private static final String NUMBER_LITERAL_INNER = NUMBER_LITERAL + "$InnerAction";
    private static final String CONST_MEDIA_ACTION = ROOT + "api/casting/castables/ConstMediaAction";
    private static final String PLAYER_CAST_ENV = ROOT + "api/casting/eval/env/PlayerBasedCastEnv";
    private static final String SPIRAL_CAST_ENV = ROOT + "api/casting/eval/env/PlayerBasedSpiralPatternCastEnv";
    private static final String STAFF_CAST_ENV = ROOT + "api/casting/eval/env/StaffCastEnv";
    private static final String DOUBLE_IOTA = ROOT + "api/casting/iota/DoubleIota";
    private static final String TREE_LIST = ROOT + "api/utils/TreeList";
    private static final String HEX_DIR = ROOT + "api/casting/math/HexDir";
    private static final String HEX_ANGLE = ROOT + "api/casting/math/HexAngle";
    private static final Map<String, String> SPECIAL_HANDLER_MATH_HASHES = Map.of(
            HEX_DIR, "1d1bce98ea4fb3387d2f0b0211725463503ef1fe33a013d1b3eb3ac337f652fe",
            HEX_ANGLE, "1fdf67e5f754ae371391dd15cfce4227473ac781963ad8bb9a44313ae0b97d62");
    private static final Map<String, String> TREE_LIST_HASHES = Map.ofEntries(
            Map.entry(TREE_LIST, "466349acce4662397d13fdce42824cf05e8bbd6337a7fe3964a14defc4056eb2"),
            Map.entry(TREE_LIST + "$BigTreeList", "b61a995e42f76fe2dd308b261d7812a4f8a70ff124911bf985a2b8ed9fe9f901"),
            Map.entry(TREE_LIST + "$TreeList0", "0e26825bde6b0cb036f72707d0f40c610cbce5bb90dce9028fd5f2d234d6ed15"),
            Map.entry(TREE_LIST + "$TreeList1", "9e1ea1401f8df221fa9df538fcbbb5a59160b693feccc1907c1c9117a9733b15"),
            Map.entry(TREE_LIST + "$TreeList2", "c23baee4cb0ebae3e72af1e440285fa7f4aa00864d9224ef992dda157b2e1815"),
            Map.entry(TREE_LIST + "$TreeList3", "381f2c0f6356e4321bf1d257e52728e772ba9202d7f3e81920d589f0cbcb2e34"),
            Map.entry(TREE_LIST + "$TreeList4", "5d9e5693e610651bb7bd63860fba5457fc9bafcb4bb3cf276a660689dd3c5993"),
            Map.entry(TREE_LIST + "$TreeList5", "7276744142983a4568c03b1b537a2767537785dd2b70f0256573a70517b5cabd"),
            Map.entry(TREE_LIST + "$TreeList6", "c4dd9c460203abf84ca6da7879c25d7a168f2e9b10172d19e3691430d03c045f"));
    private static final Map<String, String> FAST_ACTION_HASHES = Map.of(
            SPELL_ACTION, "acbafdd1ad867a0509ee20b10ae7c72c1774b7171d8d77d8542e44efc21868e2",
            ADD_MOTION, "341462d61f65dd0a115be14ed3868985d7f91888bd6ecbdab1dd7554e4147e03",
            NBT_COMPOUND, "7616e1f84d10bfad84bd1934fde392bc5ef29a216b25389643cbb2251424f4aa");
    private static final Map<String, String> NUMBER_LITERAL_HASHES = Map.of(
            NUMBER_LITERAL, "d11de498526f9e8ab671d6bcd62fced072c1b0730c8db8b94fb876d96bc6560f",
            NUMBER_LITERAL_INNER, "802cfd77a6652d509968aa6d7563d90aca84a9da9d56eed5fec72d456eadfec2",
            CONST_MEDIA_ACTION, "28d1c44cac93ba1cf8ea21e11d6c20e6274ee0296806dc2bd1aae04936067291",
            DOUBLE_IOTA, "6a07bc3c346b33b39dccc380b96e0d5912a3882eea956880980506760a69d56f");
    private static final String IOTA_TYPE_HASH = "b4f34a58fe271171c9e4a508a04abeca85e6755124d4fd0c19bf5119de64d935";
    private static final Map<String, String> DEFAULT_IOTA_METRIC_HASHES = Map.of(
            IOTA, "48da3833538c0412c73eacc135bd4014175d1c9a7d6592a58255421b3b9f3a99",
            ROOT + "api/casting/iota/BooleanIota", "b4c9fda11824dc99e7b4cb18beaf74144796cd1dcd2a3e03fd97ff907387428b",
            ROOT + "api/casting/iota/DoubleIota", "6a07bc3c346b33b39dccc380b96e0d5912a3882eea956880980506760a69d56f",
            ROOT + "api/casting/iota/EntityIota", "3e43789075dfbc1eeccc783eff95908db9b6252ee828932d51ed78b6bdbdfd0f",
            ROOT + "api/casting/iota/GarbageIota", "2e02b7a088a60dc03fecad7d59d35515f5d17f7aad8d0f1a144e2c0682170915",
            ROOT + "api/casting/iota/NullIota", "0495054c5b93e3791918c7a3d7b8186cb30b40c3602d1ca8a08bdbdc4c3500fb",
            ROOT + "api/casting/iota/PatternIota", "86bffa9188bc1ff836409d9ed5b2e3806f2efe309f658e3374595fe7f274a69a",
            ROOT + "api/casting/iota/Vec3Iota", "08d7b68e05c2d51004756d564e84fd7fd16e0b92f7c40339373f26b004520bf1");
    private static final Map<String, String> HASHES = Map.of(
            ENTITY, "6a5a5b7db211b144fe88f3079a0e1d93a0c31db069acab4ac593dce8d144e8cb",
            ROOT + "api/casting/arithmetic/engine/ArithmeticEngine", "6efec13888ffe53b9775a0374d1e337cd7f41e9d483070ca903e315288795e05",
            ROOT + "api/casting/arithmetic/engine/ArithmeticEngine$OpCandidates", "1c599f3b7122543adb70396eedd2af74a68f166fbe78181fc649497e6858a587",
            ROOT + "api/casting/iota/PatternIota", "86bffa9188bc1ff836409d9ed5b2e3806f2efe309f658e3374595fe7f274a69a",
            ROOT + "api/casting/eval/vm/CastingVM", "bf614cb94518113138c1e876566ff63a0b7d257e13151dad81d270e7cea2ab99",
            ROOT + "api/casting/eval/vm/FrameEvaluate", "302d66e09e6a6a3495333458ceeca3f33f2acc00dd25f3a0235abe0b742bb174",
            ROOT + "api/casting/eval/CastingEnvironment", "8df7b477388e046409bb59c9adbdd4beb6b9a55aa40518a352abfaa65c6d8909",
            ROOT + "common/casting/PatternRegistryManifest", "67ac1443a874c89e7c75657965110456ea08180fc1837a25f5ca073666fc7ac3",
            HEX_DIR, "1d1bce98ea4fb3387d2f0b0211725463503ef1fe33a013d1b3eb3ac337f652fe");
    private static final Map<String, Set<String>> EXECUTION_PATHS = Map.of(
            ROOT + "api/casting/arithmetic/engine/ArithmeticEngine", Set.of("run"),
            ROOT + "api/casting/iota/PatternIota", Set.of("lookupAndOperate"),
            ROOT + "api/casting/eval/vm/CastingVM", Set.of("queueExecuteAndWrapIotas", "executeInner", "performSideEffects"),
            ROOT + "api/casting/eval/vm/FrameEvaluate", Set.of("evaluate"),
            ROOT + "api/casting/eval/CastingEnvironment", Set.of("postExecution"),
            ADD_MOTION, Set.of("execute"),
            ROOT + "common/casting/PatternRegistryManifest", Set.of("processRegistry", "matchPatternToSpecialHandler"),
            HEX_DIR, Set.of("rotatedBy", "angleFrom"));
    private final Set<String> pristine = new HashSet<>();

    @Override public void onLoad(String mixinPackage) {
        try {
            Object transformer = MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
            if (transformer instanceof IMixinTransformer mixin && mixin.getExtensions() instanceof Extensions extensions) {
                extensions.add(new FinalVerifier());
                JitCompatibility.verifierInstalled();
            } else JitCompatibility.disable("final transformer verifier unavailable");
            verifyFastActionTargets();
            verifyNumberLiteralTargets();
            verifyFastStackValidationTarget();
            verifyTreeListTarget();
            verifySpecialHandlerMathTargets();
        } catch (RuntimeException | LinkageError error) {
            JitCompatibility.disable("cannot install final transformer verifier: " + error.getClass().getSimpleName());
            JitCompatibility.disableFastAction("final transformer verifier unavailable");
            JitCompatibility.disableStackValidation("final transformer verifier unavailable");
            JitCompatibility.disableFrameTailCache("final transformer verifier unavailable");
        }
    }

    private void verifyFastActionTargets() {
        try {
            for (var entry : FAST_ACTION_HASHES.entrySet()) {
                try (InputStream stream = getClass().getClassLoader().getResourceAsStream(entry.getKey() + ".class")) {
                    if (stream == null || !sha(stream.readAllBytes()).equals(entry.getValue())) {
                        JitCompatibility.disableFastAction("upstream bytecode mismatch: " + entry.getKey().replace('/', '.'));
                        return;
                    }
                }
            }
            JitCompatibility.fastActionTargetVerified();
        } catch (Exception | LinkageError error) {
            JitCompatibility.disableFastAction("cannot verify upstream Add Motion action classes");
        }
    }

    private void verifyNumberLiteralTargets() {
        try {
            for (var entry : NUMBER_LITERAL_HASHES.entrySet()) {
                try (InputStream stream = getClass().getClassLoader().getResourceAsStream(entry.getKey() + ".class")) {
                    if (stream == null || !sha(stream.readAllBytes()).equals(entry.getValue())) {
                        JitCompatibility.disableNumberLiteral("upstream bytecode mismatch: "
                                + entry.getKey().replace('/', '.'));
                        return;
                    }
                }
            }
            JitCompatibility.numberLiteralTargetVerified();
        } catch (Exception | LinkageError error) {
            JitCompatibility.disableNumberLiteral("cannot verify built-in number-literal action");
        }
    }

    private void verifyFastStackValidationTarget() {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(IOTA_TYPE + ".class")) {
            if (stream == null || !sha(stream.readAllBytes()).equals(IOTA_TYPE_HASH)) {
                JitCompatibility.disableStackValidation("upstream IotaType bytecode mismatch");
                return;
            }
            for (var entry : DEFAULT_IOTA_METRIC_HASHES.entrySet()) {
                try (InputStream iota = getClass().getClassLoader().getResourceAsStream(entry.getKey() + ".class")) {
                    if (iota == null || !sha(iota.readAllBytes()).equals(entry.getValue())) {
                        JitCompatibility.disableStackValidation("upstream Iota metrics bytecode mismatch: "
                                + entry.getKey().replace('/', '.'));
                        return;
                    }
                }
            }
            JitCompatibility.stackValidationTargetVerified();
        } catch (Exception | LinkageError error) {
            JitCompatibility.disableStackValidation("cannot verify upstream IotaType");
        }
    }

    private void verifyTreeListTarget() {
        try {
            for (var entry : TREE_LIST_HASHES.entrySet()) {
                try (InputStream stream = getClass().getClassLoader().getResourceAsStream(entry.getKey() + ".class")) {
                    if (stream == null || !sha(stream.readAllBytes()).equals(entry.getValue())) {
                        String reason = "upstream TreeList bytecode mismatch: " + entry.getKey().replace('/', '.');
                        JitCompatibility.disableFastAction(reason);
                        JitCompatibility.disableStackValidation(reason);
                        JitCompatibility.disableFrameTailCache(reason);
                        return;
                    }
                }
            }
            JitCompatibility.treeListTargetVerified();
        } catch (Exception | LinkageError error) {
            JitCompatibility.disableStackValidation("cannot verify upstream TreeList");
            JitCompatibility.disableFrameTailCache("cannot verify upstream TreeList");
        }
    }

    private void verifySpecialHandlerMathTargets() {
        try {
            for (var entry : SPECIAL_HANDLER_MATH_HASHES.entrySet()) {
                try (InputStream stream = getClass().getClassLoader().getResourceAsStream(entry.getKey() + ".class")) {
                    if (stream == null || !sha(stream.readAllBytes()).equals(entry.getValue())) {
                        JitCompatibility.disableSpecialHandlerMath("upstream enum bytecode mismatch: "
                                + entry.getKey().replace('/', '.'));
                        return;
                    }
                }
            }
        } catch (Exception | LinkageError error) {
            JitCompatibility.disableSpecialHandlerMath("cannot verify HexDir/HexAngle bytecode");
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
        if (mixin.equals(OWN + "PlayerBasedCastEnvMixin")) {
            return matchesPlayerMediaScanTarget(internal);
        }
        if (mixin.equals(OWN + "SpiralPatternSetMixin")) {
            return matchesSpiralPatternSetTarget(internal);
        }
        if (mixin.equals(OWN + "StaffCastEnvMixin")) {
            return matchesStaffOvercastTarget(internal);
        }
        if (internal.equals(TREE_LIST) || internal.startsWith(TREE_LIST + "$")) {
            String expected = TREE_LIST_HASHES.get(internal);
            if (expected != null && matchesHash(internal, expected)) return true;
            JitCompatibility.disableStackValidation("upstream TreeList nested-class bytecode mismatch: " + target);
            JitCompatibility.disableFrameTailCache("upstream TreeList nested-class bytecode mismatch: " + target);
            return false;
        }
        if (ADD_MOTION.equals(internal)) {
            String expected = FAST_ACTION_HASHES.get(ADD_MOTION);
            if (expected != null && matchesHash(internal, expected)) return true;
            JitCompatibility.disableFastAction("upstream Add Motion bytecode mismatch");
            return false;
        }
        if (NBT_COMPOUND.equals(internal)) {
            String expected = FAST_ACTION_HASHES.get(NBT_COMPOUND);
            if (matchesHash(internal, expected)) return true;
            JitCompatibility.disableFastAction("Minecraft CompoundTag bytecode mismatch");
            return false;
        }
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(internal + ".class")) {
            if (stream == null || !sha(stream.readAllBytes()).equals(HASHES.get(internal))) {
                if (HEX_DIR.equals(internal)) JitCompatibility.disableSpecialHandlerMath("HexDir bytecode mismatch");
                else if (ENTITY.equals(internal)) JitCompatibility.disableMotion("Minecraft Entity bytecode mismatch");
                else JitCompatibility.disable("upstream bytecode mismatch: " + target);
                return false;
            }
            return true;
        } catch (Exception error) {
            JitCompatibility.disable("cannot verify " + target);
            return false;
        }
    }

    private boolean matchesHash(String internal, String expected) {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(internal + ".class")) {
            return stream != null && sha(stream.readAllBytes()).equals(expected);
        } catch (Exception | LinkageError ignored) {
            return false;
        }
    }

    private boolean matchesPlayerMediaScanTarget(String internal) {
        if (!PLAYER_CAST_ENV.equals(internal)) return false;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(internal + ".class")) {
            if (stream == null) return false;
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            for (MethodNode method : node.methods) {
                if (!method.name.equals("extractMediaFromInventory") || !method.desc.equals("(JZZ)J")) continue;
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof MethodInsnNode call && call.getOpcode() == org.objectweb.asm.Opcodes.INVOKESTATIC
                            && call.owner.equals("at/petrak/hexcasting/api/utils/MediaHelper")
                            && call.name.equals("scanPlayerForMediaStuff")
                            && call.desc.equals("(Lnet/minecraft/server/level/ServerPlayer;)Ljava/util/List;"))
                        return true;
                }
            }
        } catch (Exception | LinkageError ignored) {
            return false;
        }
        return false;
    }

    private boolean matchesSpiralPatternSetTarget(String internal) {
        if (!SPIRAL_CAST_ENV.equals(internal)) return false;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(internal + ".class")) {
            if (stream == null) return false;
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            boolean recordsPatterns = false;
            boolean clearsPatterns = false;
            for (MethodNode method : node.methods) {
                if (method.name.equals("postExecution")
                        && method.desc.equals("(Lat/petrak/hexcasting/api/casting/eval/CastResult;)V")) {
                    for (AbstractInsnNode insn : method.instructions) {
                        if (insn instanceof MethodInsnNode call && call.owner.equals("java/util/Set")
                                && call.name.equals("add") && call.desc.equals("(Ljava/lang/Object;)Z")) {
                            recordsPatterns = true;
                        }
                    }
                } else if (method.name.equals("postCast")
                        && method.desc.equals("(Lat/petrak/hexcasting/api/casting/eval/vm/CastingImage;)V")) {
                    for (AbstractInsnNode insn : method.instructions) {
                        if (insn instanceof MethodInsnNode call && call.owner.equals("java/util/Set")
                                && call.name.equals("clear") && call.desc.equals("()V")) {
                            clearsPatterns = true;
                        }
                    }
                }
            }
            return recordsPatterns && clearsPatterns;
        } catch (Exception | LinkageError ignored) {
            return false;
        }
    }

    private boolean matchesStaffOvercastTarget(String internal) {
        if (!STAFF_CAST_ENV.equals(internal)) return false;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(internal + ".class")) {
            if (stream == null) return false;
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            for (MethodNode method : node.methods) {
                if (!method.name.equals("extractMediaEnvironment") || !method.desc.equals("(JZ)J")) continue;
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof MethodInsnNode call && call.name.equals("canOvercast")
                            && call.desc.equals("()Z") && call.owner.equals(STAFF_CAST_ENV)) return true;
                }
            }
        } catch (Exception | LinkageError ignored) {
            return false;
        }
        return false;
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
                    String reason = "transformed execution method: " + target + "." + method.name
                            + " at fingerprint byte " + mismatch + " (original=" + excerpt(expected, mismatch)
                            + ", transformed=" + excerpt(actual, mismatch) + ")";
            if (HEX_DIR.equals(node.name)) JitCompatibility.disableSpecialHandlerMath(reason);
            else if (ADD_MOTION.equals(node.name)) JitCompatibility.disableFastAction(reason);
            else JitCompatibility.disable(reason);
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
            if (PARTICLE_SIDE_EFFECT.equals(node.name)) {
                if (hasForeignMixin(node, Set.of("performEffect")))
                    JitCompatibility.disableParticleCoalescing(
                            "foreign Mixin changed OperatorSideEffect.Particles.performEffect");
                return;
            }
            if (VEC3.equals(node.name)) {
                if (hasForeignMixin(node, Set.of("normalize")))
                    JitCompatibility.disableFastAction("foreign Mixin changed Vec3.normalize");
                return;
            }
            if (HEX_ANGLE.equals(node.name)) {
                if (hasForeignMixin(node, Set.of("values")))
                    JitCompatibility.disableSpecialHandlerMath("foreign Mixin changed HexAngle.values");
                return;
            }
            if (NUMBER_LITERAL.equals(node.name)) {
                if (hasForeignMixin(node, Set.of("act", "getX")))
                    JitCompatibility.disableNumberLiteral("foreign Mixin changed SpecialHandlerNumberLiteral.act/getX");
                return;
            }
            if (NUMBER_LITERAL_INNER.equals(node.name)) {
                if (hasForeignMixin(node, Set.of("execute", "getArgc")))
                    JitCompatibility.disableNumberLiteral("foreign Mixin changed number-literal InnerAction");
                return;
            }
            if (CONST_MEDIA_ACTION.equals(node.name)) {
                if (hasForeignMixin(node, Set.of("operate", "executeWithOpCount", "getMediaCost")))
                    JitCompatibility.disableNumberLiteral("foreign Mixin changed ConstMediaAction semantics");
                return;
            }
            if (NBT_COMPOUND.equals(node.name)) {
                if (hasForeignMixin(node, Set.of("copy")))
                    JitCompatibility.disableFastAction("foreign Mixin changed CompoundTag.copy");
                return;
            }
            if (IOTA_TYPE.equals(node.name)) {
                if (hasForeignMixin(node, Set.of("isTooLargeToSerialize")))
                    JitCompatibility.disableStackValidation("foreign Mixin changed IotaType.isTooLargeToSerialize");
                return;
            }
            if (IOTA.equals(node.name)) {
                if (hasForeignMixin(node, Set.of("size", "depth")))
                    JitCompatibility.disableStackValidation("foreign Mixin changed Iota.size/depth");
                return;
            }
            if (DEFAULT_IOTA_METRIC_HASHES.containsKey(node.name)) {
                if (hasForeignMixin(node, Set.of("size", "depth")))
                    JitCompatibility.disableStackValidation("foreign Mixin changed default Iota metrics: " + node.name);
                if (!HASHES.containsKey(node.name)) return;
            }
            if (TREE_LIST.equals(node.name)) {
                if (hasForeignMixin(node, Set.of("get", "size")))
                    JitCompatibility.disableStackValidation("foreign Mixin changed TreeList.get/size");
                if (hasForeignMixin(node, Set.of("dropRight")))
                    JitCompatibility.disableFastAction("foreign Mixin changed TreeList.dropRight");
                if (hasForeignMixin(node, Set.of("tail")))
                    JitCompatibility.disableFrameTailCache("foreign Mixin changed TreeList.tail");
                return;
            }
            if (TREE_LIST_HASHES.containsKey(node.name)) {
                if (hasAnyForeignMixin(node)) {
                    String reason = "foreign Mixin changed a TreeList implementation: " + node.name.replace('/', '.');
                    JitCompatibility.disableStackValidation(reason);
                    JitCompatibility.disableFastAction(reason);
                    JitCompatibility.disableFrameTailCache(reason);
                }
                return;
            }
            if (HEX_DIR.equals(node.name)) {
                if (hasForeignMixin(node, Set.of("rotatedBy", "angleFrom", "values")))
                    JitCompatibility.disableSpecialHandlerMath("foreign Mixin changed HexDir rotation or values");
                else JitCompatibility.specialHandlerMathTargetVerified();
                return;
            }
            if ((ROOT + "api/casting/eval/CastingEnvironment").equals(node.name)) {
                if (hasForeignMixin(node, Set.of("postExecution")))
                    JitCompatibility.disable("foreign execution Mixin: " + node.name + ".postExecution");
                JitCompatibility.verified(node.name);
                return;
            }
            if (CASTING_VM.equals(node.name)
                    && hasForeignMixin(node, Set.of("queueExecuteAndWrapIotas", "executeInner", "performSideEffects")))
                JitCompatibility.disableStackValidation("foreign Mixin changed CastingVM stack validation call sites");
            if ((ROOT + "common/casting/PatternRegistryManifest").equals(node.name)) {
                if (hasForeignMixin(node, Set.of("matchPattern", "matchPatternToSpecialHandler")))
                    JitCompatibility.disableSpecialHandlerLookup("foreign Mixin changed PatternRegistryManifest pattern lookup");
                else JitCompatibility.specialHandlerLookupTargetVerified();
            }
            if (SPELL_ACTION.equals(node.name)) {
                if (hasForeignMixin(node, Set.of("operate", "executeWithUserdata")))
                    JitCompatibility.disableFastAction("foreign Mixin changed SpellAction.operate/executeWithUserdata");
                return;
            }
            if (ADD_MOTION.equals(node.name)) {
                if (hasForeignMixin(node, Set.of("getArgc", "execute")))
                    JitCompatibility.disableFastAction("foreign Mixin changed OpAddMotion arguments or execution");
                return;
            }
            if (!HASHES.containsKey(node.name)) return;
            if (ENTITY.equals(node.name)) {
                Set<String> motionMethods = Set.of("push", "getDeltaMovement", "setDeltaMovement", "move");
                for (MethodNode method : node.methods) {
                    if (!motionMethods.contains(method.name)) continue;
                    if (foreign(method.visibleAnnotations) || foreign(method.invisibleAnnotations)) {
                        JitCompatibility.disableMotion("foreign Mixin changed Entity." + method.name);
                        return;
                    }
                }
                JitCompatibility.motionTargetVerified();
                return;
            }
            for (MethodNode method : node.methods) {
                if (!EXECUTION_PATHS.getOrDefault(node.name, Set.of()).contains(method.name)) continue;
                if ((ROOT + "common/casting/PatternRegistryManifest").equals(node.name)
                        && method.name.equals("matchPatternToSpecialHandler")) continue;
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
        private boolean hasForeignMixin(ClassNode node, Set<String> methodNames) {
            for (MethodNode method : node.methods) {
                if (methodNames.contains(method.name)
                        && (foreign(method.visibleAnnotations) || foreign(method.invisibleAnnotations))) return true;
            }
            return false;
        }
        private boolean hasAnyForeignMixin(ClassNode node) {
            for (MethodNode method : node.methods) {
                if (foreign(method.visibleAnnotations) || foreign(method.invisibleAnnotations)) return true;
            }
            for (FieldNode field : node.fields) {
                if (foreign(field.visibleAnnotations) || foreign(field.invisibleAnnotations)) return true;
            }
            return false;
        }
        @Override public void export(MixinEnvironment environment, String name, boolean force, ClassNode node) {}
    }

    @Override public String getRefMapperConfig() { return null; }
    @Override public List<String> getMixins() { return null; }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) {}
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {
        if (mixin.equals(OWN + "PlayerBasedCastEnvMixin")) JitCompatibility.mediaPoolTargetVerified();
    }
}
