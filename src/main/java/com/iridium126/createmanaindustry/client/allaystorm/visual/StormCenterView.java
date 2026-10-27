package com.iridium126.createmanaindustry.client.allaystorm.visual;

import com.iridium126.createmanaindustry.content.allaystorm.StormCenterTimeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/** World-scoped ownership independent of activation, particle settings and GPU publication. */
public final class StormCenterView {
    private static ClientLevel world;
    private static final StormCenterTimeline TIMELINE=new StormCenterTimeline();
    private StormCenterView() {}
    public static StormCenterTimeline timeline() {
        var next=Minecraft.getInstance().level;
        if(world!=next) { world=next;TIMELINE.clear(); }
        return TIMELINE;
    }
}
