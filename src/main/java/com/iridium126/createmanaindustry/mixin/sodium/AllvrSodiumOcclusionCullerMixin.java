package com.iridium126.createmanaindustry.mixin.sodium;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.iridium126.createmanaindustry.client.dimension.render.sodium.AllvrSodiumBridge;

import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import net.minecraft.world.level.Level;

/**
 * Keeps Sodium's occlusion traversal in the same virtual Y range as its
 * RenderSection graph.  The vanilla Allay level still reports its real
 * central chunk height (-8..24), which is not the height of the virtual
 * window when the camera is in a cube.
 */
@Mixin(value = OcclusionCuller.class, remap = false)
public abstract class AllvrSodiumOcclusionCullerMixin {

    private static final int VIRTUAL_MIN_SECTION = -8192;
    private static final int VIRTUAL_MAX_SECTION = 8192;

    @Redirect(method = "init",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getMinSection()I"))
    private int cmi$virtualMinSection(Level level) {
        return AllvrSodiumBridge.active() ? VIRTUAL_MIN_SECTION : level.getMinSection();
    }

    @Redirect(method = "init",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getMaxSection()I"))
    private int cmi$virtualMaxSection(Level level) {
        return AllvrSodiumBridge.active() ? VIRTUAL_MAX_SECTION : level.getMaxSection();
    }
}
