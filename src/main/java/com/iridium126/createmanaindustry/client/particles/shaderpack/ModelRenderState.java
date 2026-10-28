package com.iridium126.createmanaindustry.client.particles.shaderpack;

import com.iridium126.createmanaindustry.mixin.iris.ModelBlendStorageAccessor;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.gl.blending.BlendMode;
import org.lwjgl.opengl.*;

/** Narrow state not covered by the shared sky/MRT and texture boundaries. */
final class ModelRenderState implements AutoCloseable {
    private final boolean locked=ModelBlendStorageAccessor.cmi$locked(),enabled=ModelBlendStorageAccessor.cmi$enabled();
    private final BlendMode blend=ModelBlendStorageAccessor.cmi$mode();
    private final int program=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),indirect=GL11.glGetInteger(GL40.GL_DRAW_INDIRECT_BUFFER_BINDING);
    private final boolean depth=GL11.glIsEnabled(GL11.GL_DEPTH_TEST),mask=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),cull=GL11.glIsEnabled(GL11.GL_CULL_FACE);
    private final float alpha=net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.getCurrentAlphaTest();
    private final boolean tessellation=net.irisshaders.iris.vertices.ImmediateState.usingTessellation;
    @Override public void close(){
        // The programs release their temporary lock before the MRT boundary
        // restores GL. Releasing it here would overwrite that restored GL state.
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER,indirect);GlStateManager._glUseProgram(program);
        RenderSystem.depthMask(mask);if(depth)RenderSystem.enableDepthTest();else RenderSystem.disableDepthTest();if(cull)RenderSystem.enableCull();else RenderSystem.disableCull();
        net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.setCurrentAlphaTest(alpha);net.irisshaders.iris.vertices.ImmediateState.usingTessellation=tessellation;
        ModelBlendStorageAccessor.cmi$enabled(enabled);ModelBlendStorageAccessor.cmi$mode(blend);ModelBlendStorageAccessor.cmi$locked(locked);
    }
}
