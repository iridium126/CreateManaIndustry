package com.iridium126.createmanaindustry.client.gpu.shader;

import com.google.gson.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import org.lwjgl.opengl.*;

/** Resource reflection belongs to program linking, never to frame submission. */
public final class GpuProgramContract {
    private static final JsonObject CONTRACT=load();
    private GpuProgramContract() {}
    private static JsonObject load(){
        try(var in=GpuProgramContract.class.getResourceAsStream("/assets/createmanaindustry/gpu-resource-contract.json")){
            if(in==null)throw new IllegalStateException("GPU resource contract is missing");
            return JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject();
        }catch(java.io.IOException failure){throw new IllegalStateException("GPU resource contract unavailable",failure);}
    }
    public static void installSamplers(int program){
        verifySharedUniformBlocks(program);
        int limit=GL11.glGetInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS);
        var occupied=new HashSet<Integer>();
        for(var entry:CONTRACT.getAsJsonObject("samplers").entrySet()){
            int index=GL43.glGetProgramResourceIndex(program,GL43.GL_UNIFORM,entry.getKey());
            if(index==GL43.GL_INVALID_INDEX)continue;
            var sampler=entry.getValue().getAsJsonObject();int[] active=new int[3];
            GL43.glGetProgramResourceiv(program,GL43.GL_UNIFORM,index,new int[]{GL43.GL_TYPE,GL43.GL_LOCATION,GL43.GL_ARRAY_SIZE},null,active);
            if(active[1]<0)continue;
            int unit=CONTRACT.getAsJsonObject("constants").get(sampler.get("unit").getAsString()).getAsInt();
            if(active[0]!=sampler.get("type").getAsInt()||active[2]!=1||unit>=limit||!occupied.add(unit))
                throw new IllegalStateException("GPU sampler contract mismatch: "+entry.getKey());
            GpuUniforms.sampler(program,entry.getKey(),unit);
        }
    }
    public static void verify(int program,String name){
        var description=CONTRACT.getAsJsonObject("programs").getAsJsonObject(name);
        if(description==null)return;
        for(var entry:description.getAsJsonObject("resources").entrySet()){
            var resource=entry.getValue().getAsJsonObject();String kind=resource.get("kind").getAsString();
            if(kind.equals("image")){
                int index=GL43.glGetProgramResourceIndex(program,GL43.GL_UNIFORM,entry.getKey());
                if(index==GL43.GL_INVALID_INDEX)throw new IllegalStateException(name+": missing image "+entry.getKey());
                int[] info=new int[2];GL43.glGetProgramResourceiv(program,GL43.GL_UNIFORM,index,new int[]{GL43.GL_TYPE,GL43.GL_LOCATION},null,info);
                int binding=resource.get("binding").getAsInt();
                if(info[1]<0||info[0]!=resource.get("type").getAsInt()||binding>=GL11.glGetInteger(GL42.GL_MAX_IMAGE_UNITS)
                    ||GL20.glGetUniformi(program,info[1])!=binding)
                    throw new IllegalStateException(name+": image contract changed: "+entry.getKey());
                continue;
            }
            boolean uniform=kind.equals("ubo");
            int api=uniform?GL43.GL_UNIFORM_BLOCK:GL43.GL_SHADER_STORAGE_BLOCK;
            int index=GL43.glGetProgramResourceIndex(program,api,entry.getKey());
            if(index==GL43.GL_INVALID_INDEX)throw new IllegalStateException(name+": missing resource "+entry.getKey());
            int[] info=new int[2];GL43.glGetProgramResourceiv(program,api,index,new int[]{GL43.GL_BUFFER_BINDING,GL43.GL_BUFFER_DATA_SIZE},null,info);
            int bindingLimit=GL11.glGetInteger(uniform?GL31.GL_MAX_UNIFORM_BUFFER_BINDINGS:GL43.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS);
            if(info[0]!=resource.get("binding").getAsInt()||info[0]>=bindingLimit)
                throw new IllegalStateException(name+": resource binding changed: "+entry.getKey());
            if(uniform&&resource.has("layout")){
                int size=CONTRACT.getAsJsonObject("layouts").getAsJsonObject(resource.get("layout").getAsString()).get("stride").getAsInt();
                if(info[1]!=size)throw new IllegalStateException(name+": uniform layout changed: "+entry.getKey());
            }
            if(resource.has("minimumBytes")&&info[1]<resource.get("minimumBytes").getAsInt())
                throw new IllegalStateException(name+": resource header changed: "+entry.getKey());
        }
    }
    private static void verifySharedUniformBlocks(int program){
        int bindingLimit=GL11.glGetInteger(GL31.GL_MAX_UNIFORM_BUFFER_BINDINGS);
        for(var entry:CONTRACT.getAsJsonObject("sharedUniformBlocks").entrySet()){
            int index=GL43.glGetProgramResourceIndex(program,GL43.GL_UNIFORM_BLOCK,entry.getKey());
            if(index==GL43.GL_INVALID_INDEX)continue;
            var block=entry.getValue().getAsJsonObject();int[] info=new int[2];
            GL43.glGetProgramResourceiv(program,GL43.GL_UNIFORM_BLOCK,index,new int[]{GL43.GL_BUFFER_BINDING,GL43.GL_BUFFER_DATA_SIZE},null,info);
            if(info[0]!=block.get("binding").getAsInt()||info[0]>=bindingLimit||info[1]!=block.get("bytes").getAsInt())
                throw new IllegalStateException("Uniform block contract mismatch: "+entry.getKey());
            for(var member:block.getAsJsonObject("members").entrySet()){
                int field=GL43.glGetProgramResourceIndex(program,GL43.GL_UNIFORM,member.getKey());
                if(field==GL43.GL_INVALID_INDEX)continue;
                int[] layout=new int[3];
                GL43.glGetProgramResourceiv(program,GL43.GL_UNIFORM,field,new int[]{GL43.GL_BLOCK_INDEX,GL43.GL_OFFSET,GL43.GL_TYPE},null,layout);
                var expected=member.getValue().getAsJsonObject();
                if(layout[0]!=index||layout[1]!=expected.get("offset").getAsInt()||layout[2]!=expected.get("type").getAsInt())
                    throw new IllegalStateException("Uniform field contract mismatch: "+entry.getKey()+"."+member.getKey());
            }
        }
    }
}
