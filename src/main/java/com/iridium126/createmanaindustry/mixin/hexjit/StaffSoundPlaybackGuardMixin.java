package com.iridium126.createmanaindustry.mixin.hexjit;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.EventHooks;
import org.spongepowered.asm.mixin.Mixin;

/** Enable final bytecode verification without intercepting ordinary sound playback. */
@Mixin({Level.class, ServerLevel.class, EventHooks.class})
public abstract class StaffSoundPlaybackGuardMixin {}
