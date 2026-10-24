package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.iota.DoubleIota;
import at.petrak.hexcasting.common.lib.hex.HexImageComponents;
import com.iridium126.createmanaindustry.compat.hexcasting.CMIHexImageComponents;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class HexImageComponentGameTests {
    @GameTest(template = "hex_jit_test")
    public static void tickCopiesPreserveOtherComponentsAndPersistThroughBothCodecs(GameTestHelper helper) {
        var ravenmind = new DoubleIota(42);
        var ticks = new CompoundTag();
        ticks.putInt("0,0,0", 4);
        var data = new CompoundTag();
        data.put("hexal:times_ticked", ticks);
        var chain = new com.iridium126.createmanaindustry.compat.trickster.casting.CastChain().snapshot();
        var original = CMIHexImageComponents.withTickData(new CastingImage(), data)
                .withComponent(HexImageComponents.RAVENMIND.get(), ravenmind)
                .withComponent(CMIHexImageComponents.CAST_CHAIN.get(), chain);
        var copy = FastCompoundTagCopy.copy(data);
        copy.getCompound("hexal:times_ticked").putInt("0,0,0", 5);
        var next = original.copy(original.getStack(), original.getParenCount(), original.getParenthesized(),
                original.getEscapeNext(), original.getSimulateNext(), original.getOpsConsumed(),
                CMIHexImageComponents.componentsWithTickData(original, copy));
        helper.assertTrue(ticks.getInt("0,0,0") == 4, "Tick copy mutated the input image");
        helper.assertTrue(next.getComponent(HexImageComponents.RAVENMIND.get()) == ravenmind
                && next.getComponent(CMIHexImageComponents.CAST_CHAIN.get()) == chain,
                "Tick copy replaced another image component");
        helper.assertTrue(CMIHexImageComponents.componentsWithTickData(next, copy) == next.getComponents(),
                "Optimized Tick needlessly rebuilt its owned component map");

        var encoded = CastingImage.getCODEC().encodeStart(NbtOps.INSTANCE, next).getOrThrow();
        var decoded = CastingImage.getCODEC().parse(NbtOps.INSTANCE, encoded).getOrThrow();
        check(helper, decoded);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            CastingImage.getSTREAM_CODEC().encode(buffer, next);
            check(helper, (CastingImage) CastingImage.getSTREAM_CODEC().decode(buffer));
        } finally {
            buffer.release();
        }
        check(helper, next.removeTransientComponents());
        helper.succeed();
    }

    private static void check(GameTestHelper helper, CastingImage image) {
        helper.assertTrue(CMIHexImageComponents.tickData(image).getCompound("hexal:times_ticked").getInt("0,0,0") == 5,
                "Tick counters did not survive the casting image boundary");
        helper.assertTrue(image.getComponent(HexImageComponents.RAVENMIND.get()) instanceof DoubleIota iota
                && iota.getDouble() == 42, "Ravenmind did not survive the casting image boundary");
        helper.assertTrue(image.getComponent(CMIHexImageComponents.CAST_CHAIN.get()) != null,
                "Chain identity did not survive the casting image boundary");
    }
}
