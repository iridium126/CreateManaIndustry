package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.iota.DoubleIota;
import at.petrak.hexcasting.common.lib.hex.HexImageComponents;
import com.iridium126.createmanaindustry.compat.hexcasting.CMIHexImageComponents;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import java.util.Map;
import net.minecraft.core.BlockPos;
import ram.talia.hexal.common.lib.hex.HexalImageComponents;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class HexImageComponentGameTests {
    @GameTest(template = "hex_jit_test")
    public static void hexalTickComponentIsTransient(GameTestHelper helper) {
        var ravenmind = new DoubleIota(42);
        var chain = new com.iridium126.createmanaindustry.compat.trickster.casting.CastChain().snapshot();
        var image = new CastingImage()
                .withComponent(HexalImageComponents.TIMES_TICKED_MAP, Map.of(BlockPos.ZERO, 5))
                .withComponent(HexImageComponents.RAVENMIND.get(), ravenmind)
                .withComponent(CMIHexImageComponents.CAST_CHAIN.get(), chain);

        var stripped = image.removeTransientComponents();
        helper.assertTrue(HexalTickOptimization.counters(image).get(BlockPos.ZERO) == 5
                && HexalTickOptimization.counters(stripped) == null, "Hexal counters must be scoped to one cast");
        var encoded = CastingImage.getCODEC().encodeStart(NbtOps.INSTANCE, stripped).getOrThrow();
        checkPersistentComponents(helper, CastingImage.getCODEC().parse(NbtOps.INSTANCE, encoded).getOrThrow());
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            CastingImage.getSTREAM_CODEC().encode(buffer, stripped);
            checkPersistentComponents(helper, (CastingImage) CastingImage.getSTREAM_CODEC().decode(buffer));
        } finally {
            buffer.release();
        }
        helper.succeed();
    }

    private static void checkPersistentComponents(GameTestHelper helper, CastingImage image) {
        helper.assertTrue(HexalTickOptimization.counters(image) == null, "Transient counters survived cast cleanup");
        helper.assertTrue(image.getComponent(HexImageComponents.RAVENMIND.get()) instanceof DoubleIota iota
                && iota.getDouble() == 42, "Ravenmind did not survive the casting image boundary");
        helper.assertTrue(image.getComponent(CMIHexImageComponents.CAST_CHAIN.get()) != null,
                "Chain identity did not survive the casting image boundary");
    }
}
