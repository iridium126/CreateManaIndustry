package com.iridium126.createmanaindustry.client.render.shaderpack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;

/** One immutable resource generation shared by pack patches and the vanilla core shaders. */
public final class RiftShaderSources {
    private static volatile String source = bundled("allay_sky.glsl")+"\n"+bundled("native_rift.glsl");
    private RiftShaderSources() {}
    public static String source() { return source; }
    private static String bundled(String name) {
        try(var stream=RiftShaderSources.class.getClassLoader().getResourceAsStream("assets/createmanaindustry/shaders/include/"+name)) {
            if(stream==null)throw new IOException(name);
            return new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        } catch(IOException error) { throw new IllegalStateException("CMI rift shader resource unavailable",error); }
    }
    public static void reload(ResourceProvider resources) throws IOException {
        StringBuilder next=new StringBuilder();
        for(String name:new String[]{"allay_sky.glsl","native_rift.glsl"}) {
            var resource=resources.getResourceOrThrow(ResourceLocation.fromNamespaceAndPath("createmanaindustry","shaders/include/"+name));
            try(var stream=resource.open()) { next.append(new String(stream.readAllBytes(),StandardCharsets.UTF_8)).append('\n'); }
        }
        source=next.toString();
    }
}
