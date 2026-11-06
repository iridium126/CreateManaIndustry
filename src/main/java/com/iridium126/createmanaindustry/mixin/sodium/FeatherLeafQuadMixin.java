package com.iridium126.createmanaindustry.mixin.sodium;

import com.iridium126.createmanaindustry.client.particles.shaderpack.FeatherLeafMesh;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = BlockRenderer.class, remap = false)
abstract class FeatherLeafQuadMixin {
    @WrapMethod(method = "renderModel")
    private void cmi$leafBlock(BakedModel model, BlockState state, BlockPos pos, BlockPos offset,
                              Operation<Void> original) {
        var build = FeatherLeafMesh.BUILD.get();
        if (build == null || !state.is(BlockTags.LEAVES)) {
            original.call(model, state, pos, offset);
            return;
        }
        var ids = WorldRenderingSettings.INSTANCE.getBlockStateIds();
        build.material = ids == null ? -1 : ids.getInt(state);
        build.block = build.material < 0 ? null : pos;
        try {
            original.call(model, state, pos, offset);
        } finally {
            build.block = null;
        }
    }

    @Inject(method = "bufferQuad", at = @At(value = "INVOKE", target =
        "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/builder/ChunkMeshBufferBuilder;push([Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;I)V"))
    private void cmi$leafQuad(CallbackInfo ci, @Local ChunkVertexEncoder.Vertex[] vertices,
                              @Local ModelQuadFacing facing, @Local(ordinal = 0) TerrainRenderPass pass) {
        var build = FeatherLeafMesh.BUILD.get();
        if (build == null || build.block == null || facing != ModelQuadFacing.POS_Y) return;
        int terrain = pass == DefaultTerrainRenderPasses.SOLID ? 1 : pass == DefaultTerrainRenderPasses.CUTOUT ? 2 : 0;
        if (terrain == 0) return;
        var block = build.block;
        int x = block.getX() - build.originX, y = block.getY() & 15, z = block.getZ() - build.originZ;
        // A collision resting at block height can only be attached to a matching render surface.
        // Reject layered, inset, and ambiguous models rather than using a guessed face.
        int corners = 0, order = 0, sky = 0;
        for (int corner = 0; corner < 4; corner++) {
            var vertex = vertices[corner];
            float u = vertex.x - x, v = vertex.z - z;
            if (vertex.y != y + 1 || (u != 0 && u != 1) || (v != 0 && v != 1)) return;
            int point = (int) u + 2 * (int) v;
            corners |= 1 << point;
            order |= point << (corner * 2);
            sky |= Math.min((vertex.light >>> 16) & 255, 240) << (corner * 8);
        }
        if (corners != 15) return;
        long key = block.asLong();
        if (build.faces.containsKey(key)) {
            build.faces.put(key, null);
            return;
        }
        build.faces.put(key, new FeatherLeafMesh.Face(terrain, build.material, order, sky));
    }
}
