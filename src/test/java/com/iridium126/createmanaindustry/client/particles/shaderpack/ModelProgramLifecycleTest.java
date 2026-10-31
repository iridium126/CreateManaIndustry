package com.iridium126.createmanaindustry.client.particles.shaderpack;

import com.iridium126.createmanaindustry.accessor.CMIPackageIrisPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.*;
import net.minecraft.client.renderer.ShaderInstance;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelProgramLifecycleTest {
    static final class Owner implements CMIPackageIrisPipeline {
        final Set<ShaderInstance> live=Collections.newSetFromMap(new IdentityHashMap<>());
        int retired;
        public ProgramSet cmi$packageProgramSet(){throw new AssertionError("Unexpected program query");}
        public boolean cmi$packageHasShadows(){return true;}
        public void cmi$discardPackageShader(ShaderInstance shader){assertTrue(live.remove(shader),"double retirement");retired++;}
        public ShaderInstance cmi$createPackageShader(String n,ProgramSource s,ProgramId id,AlphaTest a,VertexFormat f,FogMode fog,boolean i,boolean b,boolean g,boolean t,boolean ie){throw new AssertionError("Unexpected compile");}
        public ShaderInstance cmi$createPackageShadowShader(String n,ProgramSource s,ProgramId id,AlphaTest a,VertexFormat f,boolean i,boolean b,boolean t,boolean ie){throw new AssertionError("Unexpected compile");}
    }
    static ShaderInstance inertShader()throws Exception{
        var field=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);
        return (ShaderInstance)((sun.misc.Unsafe)field.get(null)).allocateInstance(ShaderInstance.class);
    }
    static void set(Object owner,String name,Object value)throws Exception{var field=owner.getClass().getDeclaredField(name);field.setAccessible(true);field.set(owner,value);}
    @Test void reloadRetiresSharedCarrierOnceAndDropsSourceCache()throws Exception{
        var compiler=new ShaderPackProgramCompiler();var owner=new Owner();var body=inertShader();var ghost=inertShader();var shadow=inertShader();
        owner.live.addAll(List.of(body,ghost,shadow));set(compiler,"owningPipeline",owner);
        set(compiler,"entitiesShader",body);set(compiler,"carrierShader",body);set(compiler,"ghostShader",ghost);set(compiler,"shadowShader",shadow);
        set(compiler,"optimizationReport","old session");set(compiler,"featherOptimization","old feather plan");compiler.reset();
        assertEquals(3,owner.retired);assertTrue(owner.live.isEmpty());assertFalse(compiler.isReady());assertNull(compiler.carrierShader());
        assertEquals(-1,compiler.carrierGtextureUnit());assertEquals("pending",compiler.optimizationReport());
        assertEquals("shared entity program; pending",compiler.featherOptimization());
        compiler.reset();assertEquals(3,owner.retired);
    }
    @Test void failedCandidateReleasesAllSuccessfulPartialPrograms()throws Exception{
        var compiler=new ShaderPackProgramCompiler();var owner=new Owner();set(compiler,"owningPipeline",owner);
        for(String name:List.of("entitiesShader","carrierShader","ghostShader")){var shader=inertShader();owner.live.add(shader);set(compiler,name,shader);}
        // Suppress the mod-entrypoint logger in the plain JVM; exercise the
        // same cleanup even when a failure was already recorded this session.
        set(compiler,"failed",true);
        var fail=ShaderPackProgramCompiler.class.getDeclaredMethod("fail",String.class);fail.setAccessible(true);assertEquals(false,fail.invoke(compiler,"injected failure"));
        assertEquals(3,owner.retired);assertTrue(owner.live.isEmpty());assertEquals("injected failure",compiler.lastError());assertFalse(compiler.isReady());
        compiler.reset();assertEquals(3,owner.retired);
    }
}
