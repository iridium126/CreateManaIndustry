package com.iridium126.createmanaindustry.mixin.allaystorm;
import com.google.common.collect.ImmutableList;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(Entity.class)
public interface RiftPassengersAccessor {@Accessor("passengers") void cmi$orderedPassengers(ImmutableList<Entity> passengers);}
