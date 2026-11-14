package com.iridium126.createmanaindustry.compat.trickster.casting;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import java.util.UUID;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.NbtOps;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class CastChainOptionalGameTests {
    @GameTest(template = "cast_chain_test")
    public static void snapshotLoadsWithoutTheOtherIntegration(GameTestHelper helper) throws Exception {
        String absent = System.getProperty("cmi.castchain.absent");
        helper.assertTrue(!ModList.get().isLoaded(absent), "Absence fixture still loaded " + absent);
        helper.assertTrue(ModList.get().isLoaded(absent.equals("trickster") ? "hexcasting" : "trickster"), "Fixture removed both mods");
        var chain = new CastChain(); chain.bind(UUID.randomUUID()); chain.addFateTarget(UUID.randomUUID());
        var snapshot = chain.snapshot();
        var encoded = CastChainSnapshot.CODEC.encodeStart(NbtOps.INSTANCE, snapshot).getOrThrow();
        helper.assertTrue(snapshot.equals(CastChainSnapshot.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow()),
                "Shared snapshot required the absent integration");
        if (absent.equals("hexal")) {
            helper.assertTrue(CreateManaIndustry.HEX_ACTIVE
                            && !com.iridium126.createmanaindustry.compat.hexcasting.jit.JitCompatibility.hexalTickReady(),
                    "Hexal Tick was enabled without Hexal");
            helper.assertTrue(at.petrak.hexcasting.xplat.IXplatAbstractions.INSTANCE.getActionRegistry()
                            .get(net.minecraft.resources.ResourceLocation.parse("createmanaindustry:tick")) == null,
                    "CMI still registers a separate Tick spell");
        } else if (absent.equals("trickster")) {
            helper.assertTrue(CreateManaIndustry.HEX_ACTIVE && !CreateManaIndustry.TRICKSTER_ACTIVE, "Compatibility gates were wrong");
            // Loading this registry must not initialize the Endec adapter.
            Class.forName("com.iridium126.createmanaindustry.compat.hexcasting.CMIHexImageComponents");
        } else {
            helper.assertTrue(!CreateManaIndustry.HEX_ACTIVE && CreateManaIndustry.TRICKSTER_ACTIVE
                            && !ModList.get().isLoaded("hexal"), "Compatibility gates were wrong");
        }
        helper.succeed();
    }
}
