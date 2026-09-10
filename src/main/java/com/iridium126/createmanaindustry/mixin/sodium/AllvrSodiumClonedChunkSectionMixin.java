package com.iridium126.createmanaindustry.mixin.sodium;

import com.iridium126.createmanaindustry.client.dimension.render.sodium.AllvrSodiumBridge;
import com.iridium126.createmanaindustry.client.dimension.render.sodium.AllvrSodiumSectionSource;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.caffeinemc.mods.sodium.client.world.cloned.ClonedChunkSection;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.DataLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Routes Sodium's cloned-section light snapshot through the ALLVR cache. */
@Mixin(value = ClonedChunkSection.class, remap = false)
public abstract class AllvrSodiumClonedChunkSectionMixin {

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
        target = "Lnet/caffeinemc/mods/sodium/client/world/cloned/ClonedChunkSection;"
            + "copyLightData(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/SectionPos;)"
            + "[Lnet/minecraft/world/level/chunk/DataLayer;"))
    private DataLayer[] cmi$allayLightData(Level level, SectionPos pos,
                                            Operation<DataLayer[]> original) {
        if (level instanceof ClientLevel clientLevel
            && clientLevel.dimension() == AllvrDimensions.ALLAY_LEVEL
            && AllvrSodiumBridge.active()) {
            return AllvrSodiumSectionSource.lightData(clientLevel, pos);
        }
        return original.call(level, pos);
    }
}
