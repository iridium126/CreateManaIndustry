package com.iridium126.createmanaindustry.client.particles.engine;

import java.util.*;
import org.lwjgl.opengl.*;

/** Link/diagnostic boundary only. Never queried during normal frame submission. */
public final class UpdateResourceInterface {
    private UpdateResourceInterface(){}
    public static final int CROSS_VIEW_BARRIER=GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
            |GL42.GL_TEXTURE_FETCH_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL42.GL_COMMAND_BARRIER_BIT;
    public record Texture(String name,int type,int unit){}
    public static final List<Texture> SAMPLERS=List.of(new Texture("uCollision",GL20.GL_SAMPLER_3D,0),
            new Texture("uStormCollisionAtlas",GL30.GL_UNSIGNED_INT_SAMPLER_3D,1),new Texture("uStormCollisionTable",GL31.GL_INT_SAMPLER_BUFFER,2),
            new Texture("cmi_AllayAnimationRead",GL31.GL_UNSIGNED_INT_SAMPLER_BUFFER,3),new Texture("cmiDomeKeys",GL31.GL_UNSIGNED_INT_SAMPLER_BUFFER,com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.SAMPLER_DOME_KEYS));
    public static final List<Texture> IMAGES=List.of(new Texture("cmi_AllayAnimationWrite",GL42.GL_UNSIGNED_INT_IMAGE_BUFFER,0),
            new Texture("cmi_AllayAnimationInitial",GL42.GL_UNSIGNED_INT_IMAGE_BUFFER,1),new Texture("cmi_ModelMembersWrite",GL42.GL_UNSIGNED_INT_IMAGE_BUFFER,2),
            new Texture("cmi_ModelAllocator",GL42.GL_UNSIGNED_INT_IMAGE_BUFFER,3),
            new Texture("cmiSpatialWrite",GL42.GL_UNSIGNED_INT_IMAGE_BUFFER,4),new Texture("cmiSpatialMeta",GL42.GL_UNSIGNED_INT_IMAGE_BUFFER,5),
            new Texture("cmiSpatialSteering",GL42.GL_UNSIGNED_INT_IMAGE_BUFFER,5),
            new Texture("cmiSpatialHints",GL42.GL_UNSIGNED_INT_IMAGE_BUFFER,6),new Texture("cmiSpatialRead",GL42.GL_UNSIGNED_INT_IMAGE_BUFFER,7));
    public record Report(Map<String,Integer> blocks,Map<String,Integer> samplers,Map<String,Integer> images){}
    public static Report verify(int program,int expectedBlocks){
        int count=GL43.glGetProgramInterfacei(program,GL43.GL_SHADER_STORAGE_BLOCK,GL43.GL_ACTIVE_RESOURCES);
        if(count>GL11.glGetInteger(GL43.GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS)||count>16)
            throw new IllegalStateException("Update active SSBO blocks "+count+", expected "+expectedBlocks);
        var blocks=new TreeMap<String,Integer>();var bindings=new HashSet<Integer>();
        for(int i=0;i<count;i++){
            int[] binding=new int[1];GL43.glGetProgramResourceiv(program,GL43.GL_SHADER_STORAGE_BLOCK,i,new int[]{GL43.GL_BUFFER_BINDING},null,binding);
            String name=GL43.glGetProgramResourceName(program,GL43.GL_SHADER_STORAGE_BLOCK,i);
            if(!bindings.add(binding[0]))throw new IllegalStateException("Overlapping active update SSBO slot "+binding[0]);blocks.put(name,binding[0]);
        }
        var samplers=verifyTextures(program,SAMPLERS,GL43.GL_MAX_COMPUTE_TEXTURE_IMAGE_UNITS);
        var images=verifyTextures(program,IMAGES,GL42.GL_MAX_IMAGE_UNITS);
        int uniforms=GL43.glGetProgramInterfacei(program,GL43.GL_UNIFORM,GL43.GL_ACTIVE_RESOURCES);
        var known=new HashSet<String>();SAMPLERS.forEach(t->known.add(t.name()));IMAGES.forEach(t->known.add(t.name()));
        for(int i=0;i<uniforms;i++){
            int[] info=new int[2];GL43.glGetProgramResourceiv(program,GL43.GL_UNIFORM,i,new int[]{GL43.GL_TYPE,GL43.GL_LOCATION},null,info);
            int type=info[0];
            // Core sampler/image GLenum ranges; 0x8dc6..8dc8 are ordinary unsigned vectors.
            boolean texture=(type>=0x8b5d&&type<=0x8b64)||(type>=0x8dc0&&type<=0x8dd8&&(type<0x8dc6||type>0x8dc8))
                    ||(type>=0x900c&&type<=0x900f)||(type>=0x9108&&type<=0x910d)||(type>=0x904c&&type<=0x906d);
            if(texture&&info[1]>=0){String name=GL43.glGetProgramResourceName(program,GL43.GL_UNIFORM,i);if(!known.contains(name))throw new IllegalStateException("Undescribed active update texture "+name);}
        }
        return new Report(Collections.unmodifiableMap(blocks),samplers,images);
    }
    private static Map<String,Integer> verifyTextures(int program,List<Texture> expected,int limitName){
        int limit=GL11.glGetInteger(limitName);
        int addressLimit=limitName==GL42.GL_MAX_IMAGE_UNITS?limit:GL11.glGetInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS);
        var active=new TreeMap<String,Integer>();var units=new HashMap<Integer,Integer>();
        for(var texture:expected){
            int index=GL43.glGetProgramResourceIndex(program,GL43.GL_UNIFORM,texture.name());if(index==GL43.GL_INVALID_INDEX)continue;
            int[] info=new int[3];GL43.glGetProgramResourceiv(program,GL43.GL_UNIFORM,index,new int[]{GL43.GL_TYPE,GL43.GL_LOCATION,GL43.GL_ARRAY_SIZE},null,info);
            if(info[0]!=texture.type()||info[2]!=1)throw new IllegalStateException("Update texture type changed: "+texture.name());
            if(texture.unit()>=addressLimit||active.size()>=limit)throw new IllegalStateException("Update texture unit exceeds hardware: "+texture.name());
            com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1i(program,info[1],texture.unit());
            Integer previous=units.put(texture.unit(),texture.type());if(previous!=null&&previous!=texture.type())throw new IllegalStateException("Update sampler type conflict at "+texture.unit());
            if(GL20.glGetUniformi(program,info[1])!=texture.unit())throw new IllegalStateException("Update texture unit was not installed");active.put(texture.name(),texture.unit());
        }
        return Collections.unmodifiableMap(active);
    }
}
