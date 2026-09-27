package com.iridium126.createmanaindustry.compat.hexparse;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class HexParseAbsentGameTests {
    @GameTest(template = "worldgen_test")
    public static void optionalHexParseCanBeAbsent(GameTestHelper helper) {
        helper.assertTrue(!ModList.get().isLoaded("hexparse"), "HexParse must be absent in this test profile");
        helper.succeed();
    }

    private HexParseAbsentGameTests() {}
}
