package com.iridium126.createmanaindustry.dimension.cube;

import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.event.entity.EntityEvent;

/** Object getters retain full coordinates; the inherited packed getters have vanilla's documented width. */
public final class AllvrEntityEnteringSectionEvent extends EntityEvent.EnteringSection {
    private final AllvrSectionCoord oldPos, newPos;
    public AllvrEntityEnteringSectionEvent(Entity entity, AllvrSectionCoord oldPos, AllvrSectionCoord newPos) {
        super(entity, oldPos.sectionPos().asLong(), newPos.sectionPos().asLong()); this.oldPos = oldPos; this.newPos = newPos;
    }
    @Override public SectionPos getOldPos() { return oldPos.sectionPos(); }
    @Override public SectionPos getNewPos() { return newPos.sectionPos(); }
}
