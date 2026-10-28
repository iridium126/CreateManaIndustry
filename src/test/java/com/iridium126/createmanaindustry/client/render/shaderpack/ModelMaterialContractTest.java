package com.iridium126.createmanaindustry.client.render.shaderpack;

import com.iridium126.createmanaindustry.client.particles.shaderpack.*;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.gl.blending.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import static org.junit.jupiter.api.Assertions.*;

class ModelMaterialContractTest {
    @Test void effectiveIdentityCanChangeAndNeverLeaksOntoHeldItems(){
        var ids=new Object2IntOpenHashMap<NamespacedId>();var allay=new NamespacedId("minecraft","allay");ids.defaultReturnValue(-1);
        for(int value:new int[]{37,90017,0,-1}){ids.put(allay,value);var context=ModelEntityContext.resolve(ids);assertEquals(value,context.entityId(false));assertEquals(0,context.entityId(true));}
        ids.removeInt(allay);assertEquals(-1,ModelEntityContext.resolve(ids).allayId());
    }
    @Test void explicitAttachmentContractSurvivesDirectiveCopy()throws Exception{
        var source=new ProgramSource("gbuffers_entities","void main(){}",null,null,null,"/* DRAWBUFFERS:035 */\nvoid main(){}",null,null,BlendModeOverride.OFF);
        var directives=source.getDirectives();assertFalse(ModelBlendPlan.resolve(directives).nativeAttachments());
        var list=net.irisshaders.iris.shaderpack.properties.ProgramDirectives.class.getDeclaredField("bufferBlendInformations");list.setAccessible(true);
        list.set(directives,java.util.List.of(new BufferBlendInformation(0,new BlendMode(770,771,0,1)),new BufferBlendInformation(5,null)));
        var copy=directives.withOverriddenDrawBuffers(directives.getDrawBuffers());
        assertTrue(ModelBlendPlan.resolve(copy).nativeAttachments());assertArrayEquals(new int[]{0,3,5},copy.getDrawBuffers());assertSame(directives.getBlendModeOverride().orElseThrow(),copy.getBlendModeOverride().orElseThrow());assertEquals(directives.getBufferBlendOverrides(),copy.getBufferBlendOverrides());
    }
    @Test void irisBlendLockAccessorsMatchInstalledStaticFields()throws Exception{
        var type=ShaderPackIntegrationAbiTest.read("net/irisshaders/iris/gl/blending/BlendModeStorage");var accessor=ShaderPackIntegrationAbiTest.read("com/iridium126/createmanaindustry/mixin/iris/ModelBlendStorageAccessor");int hooks=0;
        for(var method:accessor.methods){if(method.visibleAnnotations==null)continue;for(var annotation:method.visibleAnnotations)if(annotation.desc.endsWith("/Accessor;")){
            String name=(String)annotation.values.get(annotation.values.indexOf("value")+1);var field=type.fields.stream().filter(f->f.name.equals(name)).findFirst().orElseThrow();assertNotEquals(0,field.access&Opcodes.ACC_STATIC);assertNotEquals(0,method.access&Opcodes.ACC_STATIC);var args=org.objectweb.asm.Type.getArgumentTypes(method.desc);assertEquals(field.desc,args.length==0?org.objectweb.asm.Type.getReturnType(method.desc).getDescriptor():args[0].getDescriptor());hooks++;
        }}assertEquals(6,hooks);
    }
    @Test void vanillaSkyStagePublishesFrameBeforeMaskAndTarget()throws Exception{
        var type=ShaderPackIntegrationAbiTest.read("com/iridium126/createmanaindustry/client/allaystorm/rift/VanillaRiftRenderer");var stage=type.methods.stream().filter(m->m.name.equals("stage")).findFirst().orElseThrow();int phase=0;
        for(var instruction:stage.instructions)if(instruction instanceof org.objectweb.asm.tree.MethodInsnNode method){
            if(method.owner.endsWith("/RiftRenderFrame")&&method.name.equals("begin")){assertEquals(0,phase);phase=1;}
            if(method.owner.endsWith("/JuliaProjection")&&method.name.equals("draw")){assertEquals(1,phase);phase=2;}
            if(method.owner.endsWith("/VanillaSkyView")&&method.name.equals("draw")){assertEquals(2,phase);phase=3;}
        }assertEquals(3,phase);
    }
}
