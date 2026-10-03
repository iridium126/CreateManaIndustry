package com.iridium126.createmanaindustry.client.particles.packages;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Assign input identities before collision and force capture. Server time corrections are
 * never input identities and cannot remove, overwrite or invent client captures. */
@EventBusSubscriber(modid=CreateManaIndustry.MODID,value=Dist.CLIENT)
public final class PackageClientInputs {
    private static ClientLevel level;
    private static PackageInputTimeline timeline=new PackageInputTimeline();
    private PackageClientInputs(){}
    public static PackageInputTimeline.Range current(ClientLevel requested){
        if(level!=requested){level=requested;timeline=new PackageInputTimeline();}
        return timeline.current();
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void tick(ClientTickEvent.Post event){
        var mc=Minecraft.getInstance();current(mc.level);
        if(mc.level!=null&&!mc.isPaused()&&mc.level.tickRateManager().runsNormally())
            timeline.advance(mc.level.tickRateManager().tickrate());
    }
}
