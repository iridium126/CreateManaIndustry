package com.iridium126.createmanaindustry.compat;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class ResourceConversionAbsentGameTests {
    @GameTest(template = "conversion_test")
    public static void missingModDoesNotRegisterConversions(GameTestHelper helper) {
        String absent = System.getProperty("conversion.absent");
        helper.assertTrue(!ModList.get().isLoaded(absent), "Absence profile unexpectedly loaded " + absent);
        helper.assertTrue(!(CreateManaIndustry.HEX_ACTIVE && CreateManaIndustry.TRICKSTER_ACTIVE),
                "Both compatibility flags are active");
        for (String registryId : new String[]{"hexcasting:action", "trickster:trick"}) {
            var registry = BuiltInRegistries.REGISTRY.get(ResourceLocation.parse(registryId));
            if (registry != null) {
                helper.assertTrue(!registry.containsKey(CreateManaIndustry.modLoc("media_to_mana")),
                        "Hex conversion was registered without both mods");
                helper.assertTrue(!registry.containsKey(CreateManaIndustry.modLoc("mana_to_media")),
                        "Trick conversion was registered without both mods");
            }
        }
        helper.succeed();
    }
}
