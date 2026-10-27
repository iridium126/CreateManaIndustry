package com.iridium126.createmanaindustry.content.allaystorm.rift;

import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("createmanaindustry") @PrefixGameTestTemplate(false)
public final class RiftSkyTimeGameTests {
    @GameTest(template="rift_transport_test",timeoutTicks=40)
    public static void targetClockIsReadOnlyAndTracksSourceAcrossMidnight(GameTestHelper helper) {
        var source=helper.getLevel().getServer().overworld();var target=source.getServer().getLevel(AllvrDimensions.ALLAY_LEVEL);
        helper.assertTrue(target!=null,"Target dimension missing");
        long before=source.getDayTime();
        try {
            for(long time:new long[]{0,6000,12000,18000,24000,-6000,-24001}) {
                source.setDayTime(time);
                helper.assertTrue(target.getDayTime()==time-12000,"Half-day mismatch at "+time);
                target.setDayTime(999999);
                helper.assertTrue(source.getDayTime()==time&&target.getDayTime()==time-12000,"Target writes changed source clock");
                helper.assertTrue(target.getGameTime()==source.getGameTime(),"Game clock changed");
            }
        } finally {source.setDayTime(before);}
        helper.succeed();
    }
}
