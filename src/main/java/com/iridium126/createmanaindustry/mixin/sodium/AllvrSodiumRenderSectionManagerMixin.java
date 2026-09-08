package com.iridium126.createmanaindustry.mixin.sodium;

import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createmanaindustry.client.dimension.render.sodium.AllvrSodiumBridge;
import com.iridium126.createmanaindustry.client.dimension.render.sodium.AllvrSodiumSectionLifecycle;
import com.iridium126.createmanaindustry.client.dimension.render.sodium.AllvrSodiumSectionSource;
import com.iridium126.createmanaindustry.client.dimension.render.sodium.SodiumApi_0813_1211;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.caffeinemc.mods.sodium.client.world.cloned.ChunkRenderContext;
import net.caffeinemc.mods.sodium.client.world.cloned.ClonedChunkSectionCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

/**
 * Sodium's standard section manager remains the owner.  The hooks here only
 * replace the vanilla section presence test, build snapshot, and renderer
 * private Y range for the Allay dimension.
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class AllvrSodiumRenderSectionManagerMixin {

    @Inject(method = "onSectionAdded", at = @At("HEAD"), cancellable = true)
    private void cmi$allaySectionAdded(int x, int y, int z, CallbackInfo ci) {
        ClientLevel level = AllvrSodiumBridge.level();
        if (level != null && level.dimension() == AllvrDimensions.ALLAY_LEVEL
            && !AllvrSodiumSectionLifecycle.internalAdd()) {
            AllvrSodiumBridge.nativeSectionAdd((RenderSectionManager) (Object) this, x, y, z);
            ci.cancel();
        }
    }

    @Redirect(method = "onSectionAdded",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getSections()[Lnet/minecraft/world/level/chunk/LevelChunkSection;"))
    private LevelChunkSection[] cmi$allaySections(ChunkAccess chunk) {
        return AllvrSodiumSectionLifecycle.sectionsFor(chunk);
    }

    @Redirect(method = "onSectionAdded",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getSectionIndexFromSectionY(I)I"))
    private int cmi$allaySectionIndex(ClientLevel level, int y) {
        return AllvrSodiumSectionLifecycle.sectionIndex(level.getSectionIndexFromSectionY(y));
    }

    /**
     * Voxy installs a redirect on this exact call and assumes every render
     * section has a vanilla-height LevelChunkSection array.  ALLVR sections
     * are intentionally virtual and never belong to that array; use Sodium's
     * own state update while Allay is active, preserving Voxy's behavior in
     * every ordinary dimension.
     */
    @WrapOperation(method = "updateSectionInfo",
        at = @At(value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;"
                + "setInfo(Lnet/caffeinemc/mods/sodium/client/render/chunk/data/BuiltSectionInfo;)Z"))
    private boolean cmi$allaySetInfo(RenderSection section, BuiltSectionInfo info,
                                     Operation<Boolean> original) {
        if (AllvrSodiumBridge.active()) {
            return section.setInfo(info);
        }
        return original.call(section, info);
    }

    @WrapOperation(method = "createRebuildTask",
        at = @At(value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/world/LevelSlice;prepare(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/SectionPos;Lnet/caffeinemc/mods/sodium/client/world/cloned/ClonedChunkSectionCache;)Lnet/caffeinemc/mods/sodium/client/world/cloned/ChunkRenderContext;"))
    private ChunkRenderContext cmi$allayPrepare(Level level, SectionPos pos,
                                                ClonedChunkSectionCache cache,
                                                Operation<ChunkRenderContext> original) {
        if (level instanceof ClientLevel clientLevel
            && clientLevel.dimension() == AllvrDimensions.ALLAY_LEVEL
            && AllvrSodiumBridge.active()) {
            return AllvrSodiumSectionSource.prepare(clientLevel, pos,
                AllvrSodiumBridge.resourceRevision(), AllvrSodiumBridge.window().epoch());
        }
        return original.call(level, pos, cache);
    }

    @ModifyVariable(method = "prepareFrame", at = @At("HEAD"), argsOnly = true,
        ordinal = 0)
    private Vector3dc cmi$virtualPrepareCamera(Vector3dc camera) {
        if (AllvrSodiumBridge.active()) {
            return new org.joml.Vector3d(camera.x(),
                AllvrSodiumBridge.window().virtualCameraY(camera.y()), camera.z());
        }
        return camera;
    }

    @ModifyVariable(method = "renderLayer", at = @At("HEAD"), argsOnly = true,
        ordinal = 1)
    private double cmi$virtualDrawCameraY(double cameraY) {
        return AllvrSodiumBridge.active()
            ? AllvrSodiumBridge.window().virtualCameraY(cameraY) : cameraY;
    }

    @Redirect(method = "isOutOfGraph",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getMinSection()I"))
    private int cmi$virtualMinSection(ClientLevel level) {
        return AllvrSodiumBridge.active() ? -8192 : level.getMinSection();
    }

    @Redirect(method = "isOutOfGraph",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getMaxSection()I"))
    private int cmi$virtualMaxSection(ClientLevel level) {
        return AllvrSodiumBridge.active() ? 8192 : level.getMaxSection();
    }
}
