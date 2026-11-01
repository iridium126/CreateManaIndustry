package com.iridium126.createmanaindustry.client.particles.shaderpack;

import com.google.gson.GsonBuilder;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackSession;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.minecraft.client.Minecraft;
import top.leonx.irisveil.accessors.IrisRenderingPipelineAccessor;

/** One opt-in capture per active session revision. Never called by status/report queries. */
public final class ParticleProfileSnapshot {
    private ParticleProfileSnapshot() {}
    public static Path write(ShaderPackSession session,Object pipeline) {
        if(!session.ownsPipeline(pipeline)||!(pipeline instanceof IrisRenderingPipelineAccessor access))return null;
        try {
            var values=new TreeMap<String,String>();var options=session.options();
            if(options!=null){
                options.getOptionSet().getBooleanOptions().keySet().forEach(k->values.put(k,Boolean.toString(options.getOptionValues().getBooleanValueOrDefault(k))));
                options.getOptionSet().getStringOptions().keySet().forEach(k->values.put(k,options.getOptionValues().getStringValueOrDefault(k)));
            }
            var data=new TreeMap<String,Object>();
            data.put("schema",1);data.put("pack",Iris.getCurrentPackName());data.put("session",session.revision());
            data.put("source_sha256",session.inventory().digest());data.put("options_available",options!=null);data.put("options",values);
            data.put("options_hash",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(values.toString().getBytes(StandardCharsets.UTF_8))).substring(0,12));
            var minecraft=Minecraft.getInstance();
            data.put("world",minecraft.level==null?"none":minecraft.level.dimension().location().toString());
            data.put("size",List.of(minecraft.getWindow().getWidth(),minecraft.getWindow().getHeight()));
            // These are the environment defines used by the production MODEL patcher.
            data.put("model_environment_macros",StandardMacros.createStandardEnvironmentDefines());
            var targets=new TreeMap<String,String>();
            access.getProgramSet().getPackDirectives().getRenderTargetDirectives().getRenderTargetSettings()
                    .forEach((index,settings)->targets.put(index.toString(),settings.getInternalFormat().toString()));
            data.put("color_attachment_formats",targets);
            var entity=new net.irisshaders.iris.shaderpack.programs.ProgramFallbackResolver(access.getProgramSet())
                    .resolve(net.irisshaders.iris.shaderpack.loading.ProgramId.Entities);
            entity.ifPresent(program->data.put("model_draw_buffers",program.getDirectives().getDrawBuffers()));
            if(pipeline instanceof com.iridium126.createmanaindustry.mixin.iris.NativeSkyPipelineAccess live){
                var glFormats=new TreeMap<String,Integer>();
                var liveTargets=live.cmi$targets();
                access.getProgramSet().getPackDirectives().getRenderTargetDirectives().getRenderTargetSettings().forEach((index,settings)->{
                    var target=liveTargets.get(index);if(target!=null)glFormats.put(index.toString(),target.getInternalFormat().getGlFormat());
                });
                data.put("color_attachment_formats_gl",glFormats);
                int previous=org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
                try{
                    org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,liveTargets.getDepthTexture());
                    data.put("depth_format_gl",org.lwjgl.opengl.GL11.glGetTexLevelParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,0,org.lwjgl.opengl.GL11.GL_TEXTURE_INTERNAL_FORMAT));
                }finally{org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,previous);}
            }
            data.put("historical_log_match","unverified; compare source/options/world and macros before replay");
            Path directory=minecraft.gameDirectory.toPath().resolve("logs/cmi-particle-profiles");Files.createDirectories(directory);
            Path output=directory.resolve("session-"+session.revision()+"-"+UUID.randomUUID()+".json");
            Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(data)+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            CreateManaIndustry.LOGGER.info("[CMI profile] replay context: {}",output);
            return output;
        }catch(Exception failure){CreateManaIndustry.LOGGER.warn("[CMI profile] replay context export failed",failure);return null;}
    }
}
