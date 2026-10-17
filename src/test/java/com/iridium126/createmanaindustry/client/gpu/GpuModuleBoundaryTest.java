package com.iridium126.createmanaindustry.client.gpu;
import com.iridium126.createmanaindustry.client.gpu.profile.GpuPassTelemetry;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

class GpuModuleBoundaryTest {
    private static final String ROOT="com/iridium126/createmanaindustry/client/";
    private ClassNode read(String type)throws IOException{
        try(var in=getClass().getClassLoader().getResourceAsStream(ROOT+type+".class")){
            assertNotNull(in,type);var node=new ClassNode();new ClassReader(in).accept(node,0);return node;
        }
    }
    private void excludes(String type,String... forbidden)throws IOException{
        var node=read(type);
        for(var field:node.fields)for(String name:forbidden)assertFalse(field.desc.contains(name),type+" owns "+name);
        for(var method:node.methods){
            for(String name:forbidden)assertFalse(method.desc.contains(name),type+" exposes "+name);
            for(var instruction:method.instructions)if(instruction instanceof MethodInsnNode call)
                for(String name:forbidden)assertFalse(call.owner.contains(name)||call.desc.contains(name),type+" calls "+name);
        }
    }
    @Test void sharedMechanismsHaveNoBusinessDependency()throws IOException{
        for(String type:new String[]{"GpuCapabilities","shader/GpuUniformCache","shader/GpuShaderCompiler","shader/GpuShaderSources",
                "shader/GpuShaderResources","buffer/GpuGrowingBuffer","buffer/GpuBufferRetirement","profile/GpuTimestampRing","profile/GpuPassTelemetry","buffer/GpuReadbackBuffer","frame/GpuFrameCoordinator"})
            excludes("gpu/"+type,ROOT+"particles/",ROOT+"greattrick/",ROOT+"hex/",ROOT+"packages/",ROOT+"allaystorm/");
    }
    @Test void businessProducersUseNarrowPortsAndHexDoesNotRequireTheGeneralPool()throws IOException{
        excludes("allaystorm/StarfallGpuBuffers","ParticleRuntime","ParticleBuffers","ParticlePrograms");
        excludes("allaystorm/AllayStormRuntime","ParticleRuntime","ParticlePrograms","org/lwjgl/opengl/");
        excludes("allaystorm/StormParticlePort","ParticleRuntime","ParticleBuffers","ParticlePrograms");
        excludes("greattrick/sharedfate/SharedFateGpu","ParticleRuntime","ParticleBuffers");
        excludes("greattrick/partition/PartitionGpu","ParticleRuntime","ParticleBuffers","SharedFateGpu");
        excludes("hex/pattern/HexPatternService","ParticleRuntime","ParticlePrograms","ParticleFrameLatch");
    }
}
