package com.iridium126.createmanaindustry.client.allaystorm.rift;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import java.io.IOException;

@EventBusSubscriber(modid=CreateManaIndustry.MODID, bus=EventBusSubscriber.Bus.MOD, value=Dist.CLIENT)
public final class AllaySkyShaders {
    static ShaderInstance shader;
    static ShaderInstance clouds;
    private AllaySkyShaders() {}
    @SubscribeEvent public static void register(RegisterShadersEvent event) throws IOException {
        com.iridium126.createmanaindustry.client.render.shaderpack.RiftShaderSources.reload(event.getResourceProvider());
        event.registerShader(new ShaderInstance(event.getResourceProvider(), CreateManaIndustry.modLoc("allay_sky"), DefaultVertexFormat.POSITION), loaded -> shader = loaded);
        event.registerShader(new ShaderInstance(event.getResourceProvider(), CreateManaIndustry.modLoc("allay_clouds"), DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL), loaded -> clouds = loaded);
    }
}
