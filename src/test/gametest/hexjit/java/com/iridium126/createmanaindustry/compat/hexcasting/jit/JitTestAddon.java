package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import at.petrak.hexcasting.api.casting.ActionRegistryEntry;
import at.petrak.hexcasting.api.casting.castables.*;
import at.petrak.hexcasting.api.casting.eval.*;
import at.petrak.hexcasting.api.casting.eval.vm.*;
import at.petrak.hexcasting.api.casting.iota.*;
import at.petrak.hexcasting.api.casting.math.*;
import at.petrak.hexcasting.api.utils.TreeList;
import at.petrak.hexcasting.common.lib.HexRegistries;
import at.petrak.hexcasting.common.lib.hex.*;
import com.mojang.serialization.MapCodec;
import java.util.List;
import kotlin.Pair;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/** Standard third-party APIs only. The JIT must never need to know about these classes. */
@EventBusSubscriber(modid = "createmanaindustry")
public final class JitTestAddon {
    static final HexPattern ORDINARY = HexPattern.fromAngleString("w", HexDir.EAST);
    static final HexPattern DYNAMIC = HexPattern.fromAngleString("aqawewdq", HexDir.WEST);
    static final HexPattern THROWING = HexPattern.fromAngleString("ewdadaqw", HexDir.WEST);
    private static ResourceLocation id(String name) { return ResourceLocation.fromNamespaceAndPath("createmanaindustry", "jit_test_" + name); }
    static boolean isOrdinaryAction(Action action) { return action instanceof ExtensionAction extension && !extension.throwing; }
    static boolean isThrowingAction(Action action) { return action instanceof ExtensionAction extension && extension.throwing; }

    @SubscribeEvent public static void register(RegisterEvent event) {
        event.register(HexRegistries.ACTION, id("action"), () -> new ActionRegistryEntry(ORDINARY, new ExtensionAction(false)));
        event.register(HexRegistries.ACTION, id("throw"), () -> new ActionRegistryEntry(THROWING, new ExtensionAction(true)));
        event.register(HexRegistries.SPECIAL_HANDLER, id("dynamic"), () -> (pattern, env) -> {
            if (!pattern.equals(DYNAMIC)) return null;
            var fixture = (HexJitGameTests.TestEnvironment) env;
            fixture.trace.add("match:" + fixture.dynamicCalls++);
            int value = fixture.dynamicCalls;
            return new SpecialHandler() {
                public Action act() { return Action.makeConstantOp(new DoubleIota(value)); }
                public Component getName() { return Component.literal("dynamic fixture"); }
            };
        });
        event.register(HexRegistries.CONTINUATION_TYPE, id("frame"), () -> ExtraFrame.TYPE);
    }

    private static final class ExtensionAction implements Action {
        private final boolean throwing;
        ExtensionAction(boolean throwing) { this.throwing = throwing; }
        @Override public OperationResult operate(CastingEnvironment env, CastingImage image, SpellContinuation continuation) {
            ((HexJitGameTests.TestEnvironment) env).trace.add("extension action");
            image.getUserData().putInt("addonCalls", image.getUserData().getInt("addonCalls") + 1);
            if (throwing) throw new IllegalStateException("test addon exception");
            return new OperationResult(image.withUsedOps(3), List.of(), continuation.pushFrame(ExtraFrame.INSTANCE), HexEvalSounds.NORMAL_EXECUTE.get());
        }
        @Override public ParenthesizedOperationResult operateInParens(CastingEnvironment env, CastingImage image,
                SpellContinuation continuation, Iota iota) {
            return new ParenthesizedOperationResult(image.withNewParenthesized(iota, false), List.of(), continuation,
                    HexEvalSounds.NORMAL_EXECUTE.get(), ResolvedPatternType.ESCAPED);
        }
    }

    public static final class ExtraFrame implements ContinuationFrame {
        static final ExtraFrame INSTANCE = new ExtraFrame();
        static final ContinuationFrame.Type<ExtraFrame> TYPE = new ContinuationFrame.Type<>() {
            public MapCodec<ExtraFrame> codec() { return MapCodec.unit(INSTANCE); }
            public StreamCodec<RegistryFriendlyByteBuf, ExtraFrame> streamCodec() { return StreamCodec.unit(INSTANCE); }
        };
        public CastResult evaluate(SpellContinuation next, ServerLevel level, CastingVM vm) {
            ((HexJitGameTests.TestEnvironment) vm.getEnv()).trace.add("extension frame");
            return new CastResult(new NullIota(), next, vm.getImage().withUsedOp(), List.of(), ResolvedPatternType.EVALUATED, HexEvalSounds.NOTHING.get());
        }
        public Pair<Boolean, TreeList<Iota>> breakDownwards(TreeList<Iota> stack) { return new Pair<>(false, stack); }
        public int size() { return 0; }
        public ContinuationFrame.Type<?> getType() { return TYPE; }
    }

    static final class ExecutableIota extends Iota {
        ExecutableIota() { super(() -> (IotaType<? extends Iota>) (IotaType<?>) HexIotaTypes.NULL.get()); }
        public boolean isTruthy() { return true; }
        protected boolean toleratesOther(Iota other) { return other == this; }
        @Override public boolean executable() { return true; }
        @Override public int hashCode() { return System.identityHashCode(this); }
        @Override public Component display() { return Component.literal("addon fixture"); }
        @Override public CastResult execute(CastingVM vm, ServerLevel level, SpellContinuation continuation) {
            ((HexJitGameTests.TestEnvironment) vm.getEnv()).trace.add("extension iota");
            return new CastResult(this, continuation, vm.getImage().withUsedOp(), List.of(), ResolvedPatternType.EVALUATED, HexEvalSounds.NOTHING.get());
        }
    }
}
