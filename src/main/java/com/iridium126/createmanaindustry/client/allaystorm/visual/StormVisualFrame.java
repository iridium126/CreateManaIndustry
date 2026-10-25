package com.iridium126.createmanaindustry.client.allaystorm.visual;

import com.iridium126.createmanaindustry.content.allaystorm.StormIdentity;
import net.minecraft.world.phys.Vec3;

/** CPU parameters captured only at successful GPU publication. Rejected candidates never advance this view. */
public record StormVisualFrame(StormIdentity identity,long frame,long generation,float timeSeconds,
        Vec3 center,double radius,float rotationPhase,float circulationPhase) {
    private static StormVisualFrame committed;
    public static StormVisualFrame current(){return committed;}
    public static void publish(StormVisualFrame frame){committed=frame;}
    public static void clear(){committed=null;}
}
