package com.iridium126.createmanaindustry.mixin.sodium;

import com.iridium126.createmanaindustry.client.dimension.render.sodium.AllvrSodiumBridge;
import com.iridium126.createmanaindustry.client.dimension.render.sodium.AllvrSodiumSectionSource;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.caffeinemc.mods.sodium.client.world.cloned.ClonedChunkSection;
import net.caffeinemc.mods.sodium.client.world.SodiumAuxiliaryLightManager;
import net.caffeinemc.mods.sodium.client.services.PlatformLevelAccess;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.DataLayer;
import it.unimi.dsi.fastutil.ints.Int2ReferenceMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Routes Sodium's cloned-section light snapshot through the ALLVR cache. */
@Mixin(value = ClonedChunkSection.class, remap = false)
public abstract class AllvrSodiumClonedChunkSectionMixin {

    /** Marker for cube sections, which have no LevelChunk auxiliary light owner. */
    private static final SodiumAuxiliaryLightManager ALLAY_AUX_LIGHT_MANAGER =
        new SodiumAuxiliaryLightManager() {};

    /**
     * ClonedChunkSection normally asks the LevelChunk for NeoForge's
     * auxiliary light manager.  Passing a null/empty carrier would make that
     * platform hook dereference a fake chunk, so provide the marker object
     * directly and keep the cube completely out of ClientChunkCache.
     */
    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
        target = "Lnet/caffeinemc/mods/sodium/client/services/PlatformLevelAccess;"
            + "getLightManager(Lnet/minecraft/world/level/chunk/LevelChunk;"
            + "Lnet/minecraft/core/SectionPos;)Lnet/caffeinemc/mods/sodium/client/world/SodiumAuxiliaryLightManager;"))
    private SodiumAuxiliaryLightManager cmi$allayAuxiliaryLightManager(
                                            PlatformLevelAccess access, LevelChunk carrier,
                                            SectionPos pos,
                                            Operation<SodiumAuxiliaryLightManager> original) {
        if (carrier == null || (!com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits.isVanillaSection(pos.getY()) && AllvrSodiumBridge.active()
            && AllvrSodiumBridge.level() != null
            && AllvrSodiumBridge.level().dimension() == AllvrDimensions.ALLAY_LEVEL)) {
            return ALLAY_AUX_LIGHT_MANAGER;
        }
        return original.call(access, carrier, pos);
    }

    /** Replace Sodium's LevelChunk block-entity snapshot with the cube map. */
    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
        target = "Lnet/caffeinemc/mods/sodium/client/world/cloned/ClonedChunkSection;"
            + "tryCopyBlockEntities(Lnet/minecraft/world/level/chunk/LevelChunk;"
            + "Lnet/minecraft/core/SectionPos;)Lit/unimi/dsi/fastutil/ints/Int2ReferenceMap;"))
    private Int2ReferenceMap<BlockEntity> cmi$allayBlockEntities(
                                            LevelChunk carrier, SectionPos pos,
                                            Operation<Int2ReferenceMap<BlockEntity>> original) {
        if (carrier == null || (!com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits.isVanillaSection(pos.getY()) && AllvrSodiumBridge.active()
            && AllvrSodiumBridge.level() != null
            && AllvrSodiumBridge.level().dimension() == AllvrDimensions.ALLAY_LEVEL)) {
            return AllvrSodiumSectionSource.blockEntities(pos);
        }
        return original.call(carrier, pos);
    }

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
        target = "Lnet/caffeinemc/mods/sodium/client/world/cloned/ClonedChunkSection;"
            + "copyLightData(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/SectionPos;)"
            + "[Lnet/minecraft/world/level/chunk/DataLayer;"))
    private DataLayer[] cmi$allayLightData(Level level, SectionPos pos,
                                            Operation<DataLayer[]> original) {
        if (level instanceof ClientLevel clientLevel
            && clientLevel.dimension() == AllvrDimensions.ALLAY_LEVEL
            && AllvrSodiumBridge.active()
            && !com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits.isVanillaSection(pos.getY())) {
            return AllvrSodiumSectionSource.lightData(clientLevel, pos);
        }
        return original.call(level, pos);
    }
}
