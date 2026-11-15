package com.iridium126.createmanaindustry.mixin.kinetics;

import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.TorquePropagator;

import net.minecraft.world.level.LevelAccessor;

@Mixin(value = TorquePropagator.class, remap = false)
public interface TorquePropagatorAccessor {
    @Accessor("networks")
    static Map<LevelAccessor, Map<Long, KineticNetwork>> createmanaindustry$getNetworks() {
        throw new AssertionError();
    }
}
