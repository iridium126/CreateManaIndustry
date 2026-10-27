package com.iridium126.createmanaindustry.content.allaystorm.rift;

import com.iridium126.createmanaindustry.content.allaystorm.*;
import net.minecraft.world.level.storage.ServerLevelData;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class AllayLevelDataTest {
    @Test void halfDayIsAReadOnlyViewAndNeverOffsetsSimulationTime() {
        var day=new AtomicLong(6000);
        var source=(ServerLevelData)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ServerLevelData.class},(proxy,method,args)->switch(method.getName()) {
            case "getDayTime" -> day.get();case "getGameTime" -> 99123L;
            case "getDayTimeFraction" -> .25f;case "getDayTimePerTick" -> .5f;
            default -> throw new AssertionError("Unexpected source mutation/query: "+method.getName());
        });
        var target=new AllayLevelData(null,source);
        assertEquals(-6000,target.getDayTime());assertEquals(99123,target.getGameTime());
        assertEquals(.25f,target.getDayTimeFraction());assertEquals(.5f,target.getDayTimePerTick());
        target.setDayTime(30000);target.setGameTime(999);target.setDayTimeFraction(.9f);target.setDayTimePerTick(4);
        assertEquals(6000,day.get());assertEquals(99123,target.getGameTime());
        day.set(24000);assertEquals(12000,target.getDayTime());
    }
    @Test void cloudPhaseDoesNotJumpAtTheMembersRotationModuloBoundary() {
        long wrap=(long)(2*Math.PI*16/AllayStormData.SPIN_K*20);
        var before=AllayStormPhases.evaluate(16,16,0,0,wrap,16384,0);
        var after=AllayStormPhases.evaluate(16,16,0,0,wrap+1,16384,0);
        assertTrue(before.rotation()>6);assertTrue(after.rotation()<.1);
        assertTrue(after.cloudRotation()>before.cloudRotation());
        assertTrue(after.cloudRotation()-before.cloudRotation()<.01);
    }
}
