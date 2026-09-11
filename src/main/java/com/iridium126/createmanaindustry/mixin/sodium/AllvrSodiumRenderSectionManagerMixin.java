package com.iridium126.createmanaindustry.mixin.sodium;

import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
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
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.caffeinemc.mods.sodium.client.world.cloned.ChunkRenderContext;
import net.caffeinemc.mods.sodium.client.world.cloned.ClonedChunkSectionCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.Minecraft;
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
/*
 * Voxy also redirects RenderSection#setInfo in updateSectionInfo so it can
 * ingest ordinary Sodium uploads.  Our Allay sections are virtual and must
 * never enter that path: Voxy's callback indexes a real LevelChunk using the
 * virtual Y and can throw when a section is removed.  Apply this mixin before
 * Voxy so the wrap below remains the outer operation and can short-circuit
 * that redirect for Allay while preserving Voxy in every other dimension.
 */
@Mixin(value = RenderSectionManager.class, remap = false, priority = 1500)
public abstract class AllvrSodiumRenderSectionManagerMixin {

    @Shadow @Final private ClientLevel level;

    @Inject(method = "onSectionAdded", at = @At("HEAD"), cancellable = true)
    private void cmi$allaySectionAdded(int x, int y, int z, CallbackInfo ci) {
        ClientLevel level = this.level;
        if (level == null) {
            level = Minecraft.getInstance().level;
        }
        if (level == null) {
            level = AllvrSodiumBridge.level();
        }
        if (level != null && level.dimension() == AllvrDimensions.ALLAY_LEVEL) {
            if (AllvrSodiumSectionLifecycle.internalAdd()) {
                return;
            }
            // The cube bridge owns the virtual section coordinates. Reject
            // vanilla's column onChunkAdded sweep instead of registering
            // placeholders at the client's formal-height Y values. The
            // bridge enters the scoped internalAdd path for its own nodes.
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

    /**
     * Voxy's optional DefaultChunkRenderer hook consumes the CameraTransform
     * created by this method. Its no-shader path calls Voxy setup with that
     * already-window-relative Y, while the solid hook receives the absolute
     * Y from SodiumWorldRenderer. Mark only this nested call so Voxy can
     * distinguish the two coordinate frames without changing Sodium's own
     * camera math.
     */
    @WrapOperation(method = "renderLayer",
        at = @At(value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderer;"
                + "render(Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderMatrices;"
                + "Lnet/caffeinemc/mods/sodium/client/gl/device/CommandList;"
                + "Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/ChunkRenderListIterable;"
                + "Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;"
                + "Lnet/caffeinemc/mods/sodium/client/render/viewport/CameraTransform;Z)V"))
    private void cmi$markVoxyWindowCamera(ChunkRenderer renderer,
                                          ChunkRenderMatrices matrices,
                                          CommandList commandList,
                                          ChunkRenderListIterable renderLists,
                                          TerrainRenderPass pass,
                                          CameraTransform camera,
                                          boolean indexedRenderingEnabled,
                                          Operation<Void> original) {
        if (AllvrSodiumBridge.active()) {
            AllvrSodiumBridge.enterVoxyWindowCameraFrame();
            try {
                original.call(renderer, matrices, commandList, renderLists, pass,
                    camera, indexedRenderingEnabled);
            } finally {
                AllvrSodiumBridge.exitVoxyWindowCameraFrame();
            }
            return;
        }
        original.call(renderer, matrices, commandList, renderLists, pass,
            camera, indexedRenderingEnabled);
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
