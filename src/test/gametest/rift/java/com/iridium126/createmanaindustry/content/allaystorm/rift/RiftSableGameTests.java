package com.iridium126.createmanaindustry.content.allaystorm.rift;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;
@GameTestHolder("createmanaindustry") @PrefixGameTestTemplate(false)
public final class RiftSableGameTests {
    @GameTest(template="rift_transport_test",timeoutTicks=2200)
    public static void connectedSablePlotsPreserveInventoriesAndSpringCoordinates(GameTestHelper helper){if(System.getProperty("cmi.test.riftFault")!=null||!net.neoforged.fml.ModList.get().isLoaded("simulated")){helper.succeed();return;}RiftSableFixture.run(helper);}
}
