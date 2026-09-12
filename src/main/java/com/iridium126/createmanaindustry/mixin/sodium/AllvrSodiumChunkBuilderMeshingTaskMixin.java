package com.iridium126.createmanaindustry.mixin.sodium;

import com.iridium126.createmanaindustry.client.dimension.render.sodium.AllvrSodiumBridge;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keeps Sodium's section graph virtual while model queries use absolute Y. */
@Mixin(value = ChunkBuilderMeshingTask.class, remap = false)
public abstract class AllvrSodiumChunkBuilderMeshingTaskMixin {

    @WrapOperation(
        method = "execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;"
            + "Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)"
            + "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;",
        at = @At(value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;getOriginY()I"))
    private int cmi$absoluteBuildOriginY(RenderSection section,
                                          Operation<Integer> original) {
        int virtualY = original.call(section);
        return AllvrSodiumBridge.active()
            ? AllvrSodiumBridge.window().absoluteBlockY(virtualY) : virtualY;
    }
}
