package com.iridium126.createmanaindustry.content.allaystorm.rift;

import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.WorldData;

/** Read-only half-day view. Game time, weather and calendar storage remain owned by the source. */
public final class AllayLevelData extends DerivedLevelData {
    private final ServerLevelData source;
    public AllayLevelData(WorldData world,ServerLevelData source) { super(world,source);this.source=source; }
    @Override public long getDayTime() { return source.getDayTime()-12000L; }
    @Override public void setDayTimeFraction(float fraction) { }
    @Override public void setDayTimePerTick(float rate) { }
}
