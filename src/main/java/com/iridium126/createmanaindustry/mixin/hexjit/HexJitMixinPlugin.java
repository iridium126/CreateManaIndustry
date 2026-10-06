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
    private static final Map<String, String> SOUND_EMISSION_HASHES = Map.of(
            "net/minecraft/world/level/Level", "1e1a6f9d6b23b554dd5b6ff7741b9a199f55bc6a8ea5750931d555a22c4ee320",
            "net/minecraft/server/level/ServerLevel", "e4e249f6a71830ab4eda69b25ea10cfe3a3e0af5d84a6926016cab18775c4c32",
            "net/neoforged/neoforge/event/EventHooks", "f810377f87c95c04bb4de7a7ac68e8f4862f9ca33a34cc74e067e65e2a92d66b");
    private static final String ATTRIBUTE_INSTANCE = "net/minecraft/world/entity/ai/attributes/AttributeInstance";
    private static final Map<String, String> PURE_QUOTE_HASHES = Map.of(
            ATTRIBUTE_INSTANCE, "18813b9e6efba2c6bfd07d3729ac36cecc2930d89d620db482569b852550a90c",
            ROOT + "api/casting/eval/vm/CastingImage", "51d0b18e7b40a64df8c8a16f115ef3911d4b190a68297349d7f4f80018f7098c",
            ROOT + "api/casting/eval/vm/CastingImage$ParenthesizedIota", "ba696c7f8978601d06c3b0a871548570bb76156d71e26d3b7d7e48b721413f17",
            ROOT + "api/casting/eval/CastResult", "6b678710743b1f1fbccd584be7a336fda7eb26de88ae4dd0eaaa813556476e4f",
            ROOT + "api/casting/eval/sideeffects/EvalSound", "3281300fd2eb20840d6ccdc8275ca80d69a4c3f314476eb218b92aa9688d9de7");
    private static final String ATTRIBUTE_MAP = "net/minecraft/world/entity/ai/attributes/AttributeMap";
    private static final String ATTRIBUTE_SUPPLIER = "net/minecraft/world/entity/ai/attributes/AttributeSupplier";
    private static final String LIVING_ENTITY = "net/minecraft/world/entity/LivingEntity";
    private static final Map<String, String> RANGE_ATTRIBUTE_HASHES = Map.of(
            ATTRIBUTE_MAP, "daf7e58d430b01eafbfcf520b039ef831c238929681d3131bdae531f191307df",
            ATTRIBUTE_SUPPLIER, "2341bebf54c79015b1a069a9c6def1f20ed5d2f69ee719f21e51561777b27c29",
            LIVING_ENTITY, "8ea049e9e714d91819947462b0700077ed1e2a2a1b71a758fe70c088540a5e31");
    private static final String SIMPLE_CRITERION_TRIGGER = "net/minecraft/advancements/critereon/SimpleCriterionTrigger";
    private static final String PERSONAL_MANA_HOLDER = "io/yukkuric/hexop/personal_mana/PersonalManaHolder";
    private static final String SPEND_MEDIA_TRIGGER = ROOT + "api/advancements/SpendMediaTrigger";
    private static final String PLAYER = "net/minecraft/world/entity/player/Player";
    private static final String STAT_TYPE = "net/minecraft/stats/StatType";
    private static final Map<String, String> STAT_LOOKUP_HASHES = Map.of(
            PLAYER, "1e68a34a6c5a3513d52cf80b2d2a8e82f890b3cbd3943d7f2fe8940949890747",
            STAT_TYPE, "ec5fc1c60905420942a73e096d83645c548bd98770b0e4525d3149f1aa6154ee");
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
    private static final Map<String, String> STAFF_CALLBACK_HASHES = Map.of(
            PLAYER_CAST_ENV, "60f7f6d942eb84091486a6305493d31c79232d53cac6f7c49abeaa54c348ec79",
            SPIRAL_CAST_ENV, "2bf865fa75646954f9f75b9a4bda820c92042bb1516d3388cd33801472c3489b",
            STAFF_CAST_ENV, "947b515182bd03b25047cdeaa522d86c23e199d6b8cc5503c3a24aa40f85d226");
    private static final String DOUBLE_IOTA = ROOT + "api/casting/iota/DoubleIota";
    private static final String TREE_LIST = ROOT + "api/utils/TreeList";
    private static final String HEX_DIR = ROOT + "api/casting/math/HexDir";
    private static final String HEX_ANGLE = ROOT + "api/casting/math/HexAngle";
    private static final String HEX_UTILS = ROOT + "api/utils/HexUtils";
    private static final String HEX_UTILS_HASH = "c639728299f4283cf97e21d991a455d910e1ce73c56640b3749cd17819f11bbd";
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
            ROOT + "api/casting/iota/ContinuationIota", "7e25d88bd3ac10af9ed7d78c554d7eba9837a5d158b128f4d7d02c631e8b54ff",
            ROOT + "api/casting/iota/DoubleIota", "6a07bc3c346b33b39dccc380b96e0d5912a3882eea956880980506760a69d56f",
            ROOT + "api/casting/iota/EntityIota", "3e43789075dfbc1eeccc783eff95908db9b6252ee828932d51ed78b6bdbdfd0f",
            ROOT + "api/casting/iota/GarbageIota", "2e02b7a088a60dc03fecad7d59d35515f5d17f7aad8d0f1a144e2c0682170915",
            ROOT + "api/casting/iota/ListIota", "4113c0837f879a5e4be13b2568c3429f42980addb21841ce545ba9330762c291",
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
            if (matchesHash(IOTA, DEFAULT_IOTA_METRIC_HASHES.get(IOTA))
                    && matchesHash(ROOT + "api/casting/iota/Vec3Iota",
                    DEFAULT_IOTA_METRIC_HASHES.get(ROOT + "api/casting/iota/Vec3Iota")))
                JitCompatibility.verifiedQuotedVectors();
            for (var target : STAFF_CALLBACK_HASHES.entrySet()) {
                if (!matchesHash(target.getKey(), target.getValue()))
                    JitCompatibility.disableStaffCallbacks("Staff callback bytecode mismatch: " + target.getKey());
            }
            if (STAT_LOOKUP_HASHES.entrySet().stream().allMatch(target -> matchesHash(target.getKey(), target.getValue())))
                JitCompatibility.verifiedStatLookup();
            if (RANGE_ATTRIBUTE_HASHES.entrySet().stream().allMatch(target -> matchesHash(target.getKey(), target.getValue())))
                JitCompatibility.verifiedRangeAttributes();
            if (PURE_QUOTE_HASHES.entrySet().stream().allMatch(target -> matchesHash(target.getKey(), target.getValue())))
                JitCompatibility.verifiedPureQuotes();
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
        if (mixin.equals(OWN + "StaffSoundPlaybackGuardMixin"))
            return SOUND_EMISSION_HASHES.containsKey(internal) && matchesHash(internal, SOUND_EMISSION_HASHES.get(internal));
        if (mixin.equals(OWN + "RangeAttributeInstanceAccessor"))
            return ATTRIBUTE_INSTANCE.equals(internal) && matchesHash(internal, PURE_QUOTE_HASHES.get(internal));
        if (mixin.equals(OWN + "RangeAttributeMapAccessor") || mixin.equals(OWN + "RangeAttributeSupplierAccessor"))
            return RANGE_ATTRIBUTE_HASHES.containsKey(internal) && matchesHash(internal, RANGE_ATTRIBUTE_HASHES.get(internal));
        if (mixin.equals(OWN + "PlayerStatLookupMixin"))
            return PLAYER.equals(internal) && matchesHash(internal, STAT_LOOKUP_HASHES.get(internal));
        if (mixin.equals(OWN + "SimpleCriterionTriggerAccessor"))
            return SIMPLE_CRITERION_TRIGGER.equals(internal);
        if (mixin.equals(OWN + "SimpleCriterionTriggerMixin"))
            return SIMPLE_CRITERION_TRIGGER.equals(internal);
        if (mixin.equals(OWN + "SpendMediaTriggerMixin"))
            return SPEND_MEDIA_TRIGGER.equals(internal);
        if (mixin.equals(OWN + "PersonalManaHolderMixin"))
            return matchesPersonalManaHolderTarget(internal);
        if (mixin.equals(OWN + "HexUtilsResourceKeyMixin")) {
            if (HEX_UTILS.equals(internal) && matchesHash(internal, HEX_UTILS_HASH)) return true;
            JitCompatibility.disableActionResourceKeyCache("upstream HexUtils bytecode mismatch");
            JitCompatibility.disableActionTagMembership("upstream HexUtils bytecode mismatch");
            return false;
        }
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

    private boolean matchesPersonalManaHolderTarget(String internal) {
        if (!PERSONAL_MANA_HOLDER.equals(internal)) return false;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(internal + ".class")) {
            if (stream == null) return false;
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            boolean readsMedia = false;
            boolean writesMedia = false;
            for (MethodNode method : node.methods) {
                readsMedia |= method.name.equals("getMedia") && method.desc.equals("()J");
                writesMedia |= method.name.equals("setMedia") && method.desc.equals("(J)V");
            }
            return readsMedia && writesMedia;
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
            if (SOUND_EMISSION_HASHES.containsKey(node.name)) {
                for (MethodNode method : original.methods) {
                    if (!method.name.equals("playSound") && !method.name.equals("playSeededSound")) continue;
                    MethodNode current = node.methods.stream().filter(m -> m.name.equals(method.name) && m.desc.equals(method.desc))
                            .findFirst().orElseThrow();
                    if (!Arrays.equals(instructions(method), instructions(current))) JitCompatibility.disableSoundElision();
                }
            }
            for (MethodNode method : original.methods) {
                if (!EXECUTION_PATHS.getOrDefault(node.name, Set.of()).contains(method.name)
                        && !(STAFF_CALLBACK_HASHES.containsKey(node.name) && method.name.equals("postExecution"))) continue;
                MethodNode current = node.methods.stream().filter(m -> m.name.equals(method.name) && m.desc.equals(method.desc))
                        .findFirst().orElseThrow();
                byte[] expected = instructions(method);
                byte[] actual = instructions(current);
                if (!Arrays.equals(expected, actual)) {
                    int mismatch = firstDifference(expected, actual);
                    String reason = "transformed execution method: " + target + "." + method.name
                            + " at fingerprint byte " + mismatch + " (original=" + excerpt(expected, mismatch)
                            + ", transformed=" + excerpt(actual, mismatch) + ")";
            if (STAFF_CALLBACK_HASHES.containsKey(node.name)) JitCompatibility.disableStaffCallbacks(reason);
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
            if (SOUND_EMISSION_HASHES.containsKey(node.name)) {
                if (!originalMethods(node, Set.of("playSound", "playSeededSound", "onPlaySoundAtPosition"))) JitCompatibility.disableSoundElision();
                else JitCompatibility.verifiedSoundEmission(node.name);
            }
            if (PURE_QUOTE_HASHES.containsKey(node.name) && hasAnyForeignMixin(node)) {
                JitCompatibility.disablePureQuotes();
                if (node.name.startsWith(ROOT)) {
                    JitCompatibility.disableQuotedVectors();
                    JitCompatibility.disableStaffCallbacks("foreign image/result/sound Mixin");
                    JitCompatibility.disableFastAction("foreign image/result/sound Mixin");
                }
            }
            if ((ATTRIBUTE_MAP.equals(node.name) || ATTRIBUTE_SUPPLIER.equals(node.name)) && hasAnyForeignMixin(node))
                JitCompatibility.disableRangeAttributes();
            if (LIVING_ENTITY.equals(node.name) && !originalMethods(node, Set.of("getAttributeValue", "getAttributes")))
                JitCompatibility.disableRangeAttributes();
            if (LIVING_ENTITY.equals(node.name) && node.fields.stream().anyMatch(field -> field.name.equals("attributes")
                    && (field.access & org.objectweb.asm.Opcodes.ACC_FINAL) == 0)) JitCompatibility.disableRangeAttributes();
            if ((IOTA.equals(node.name) || (ROOT + "api/casting/iota/Vec3Iota").equals(node.name))
                    && hasAnyForeignMixin(node)) JitCompatibility.disableQuotedVectors();
            if (STAT_TYPE.equals(node.name) && hasAnyForeignMixin(node)) JitCompatibility.disableStatLookup();
            if (STAFF_CALLBACK_HASHES.containsKey(node.name)) {
                if (hasAnyForeignMixin(node)) JitCompatibility.disableStaffCallbacks("foreign Staff callback Mixin");
                else if (STAFF_CAST_ENV.equals(node.name)
                        && node.methods.stream().noneMatch(method -> method.name.equals("cmi$postSuccessfulTick")))
                    JitCompatibility.disableStaffCallbacks("Staff callback bridge unavailable");
                else JitCompatibility.verifiedStaffCallback(node.name);
            }
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
                if (hasForeignMixin(node, Set.of("init")))
                    JitCompatibility.disableFastAction("foreign Mixin changed TreeList.init");
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
                if (hasForeignMixin(node, Set.of("precheckAction", "getCostModifier", "actionKey")))
                    JitCompatibility.disableActionPrechecks(
                            "foreign Mixin changed CastingEnvironment action prechecks or cost modifiers");
                else JitCompatibility.actionPrecheckTargetVerified();
                if (hasForeignMixin(node, Set.of("postExecution")))
                    JitCompatibility.disable("foreign execution Mixin: " + node.name + ".postExecution");
                if (hasForeignMixin(node, Set.of("extractMedia")))
                    JitCompatibility.disableDirectMediaPreflight("foreign Mixin changed CastingEnvironment.extractMedia");
                else JitCompatibility.directPreflightCastingEnvironmentVerified();
                JitCompatibility.verified(node.name);
                return;
            }
            if (PLAYER_CAST_ENV.equals(node.name)) {
                if (hasForeignMixin(node, Set.of("extractMediaFromInventory", "canOvercast")))
                    JitCompatibility.disableDirectMediaPreflight("foreign Mixin changed PlayerBasedCastEnv media extraction");
                else JitCompatibility.directPreflightPlayerEnvironmentVerified();
                return;
            }
            if (STAFF_CAST_ENV.equals(node.name)) {
                if (hasForeignMixin(node, Set.of("extractMediaEnvironment")))
                    JitCompatibility.disableDirectMediaPreflight("foreign Mixin changed StaffCastEnv media extraction");
                else JitCompatibility.directPreflightStaffEnvironmentVerified();
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
        private boolean originalMethods(ClassNode node, Set<String> names) {
            try (InputStream stream = getClass().getClassLoader().getResourceAsStream(node.name + ".class")) {
                if (stream == null) return false;
                ClassNode original = new ClassNode();
                new ClassReader(stream).accept(original, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                for (MethodNode method : original.methods) {
                    if (!names.contains(method.name)) continue;
                    MethodNode current = node.methods.stream().filter(m -> m.name.equals(method.name) && m.desc.equals(method.desc))
                            .findFirst().orElseThrow();
                    if (!Arrays.equals(instructions(method), instructions(current))) return false;
                }
                return true;
            } catch (Exception ignored) { return false; }
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
        if (mixin.equals(OWN + "PersonalManaHolderMixin")) JitCompatibility.personalMediaBatchTargetVerified();
        if (mixin.equals(OWN + "HexUtilsResourceKeyMixin")) JitCompatibility.actionResourceKeyCacheTargetVerified();
        if (mixin.equals(OWN + "HexUtilsResourceKeyMixin")) JitCompatibility.actionTagMembershipTargetVerified();
    }
}
