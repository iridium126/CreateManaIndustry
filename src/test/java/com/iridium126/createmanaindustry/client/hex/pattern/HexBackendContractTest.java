package com.iridium126.createmanaindustry.client.hex.pattern;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;
import static org.junit.jupiter.api.Assertions.*;
class HexBackendContractTest {
    static ClassNode type(String name)throws Exception{try(var in=HexBackendContractTest.class.getClassLoader().getResourceAsStream(name+".class")){assertNotNull(in,name);var c=new ClassNode();new ClassReader(in).accept(c,0);return c;}}
    @Test void configuredBudgetAndAccounting(){assertEquals(16,ClientConfig.hexPatternGpuBudgetMiB);var b=new HexGpuBudget(16L<<20);assertTrue(b.reserve(100));assertFalse(b.reserve(16L<<20));assertEquals(100,b.used());b.release(100);assertEquals(0,b.used());assertThrows(IllegalStateException.class,()->b.release(1));}
    @Test void legacyPatternResourcesAreAbsentAndParticleAbiIsStable(){
        var loader=getClass().getClassLoader();for(String name:List.of("HexPatternRuntime","HexPatternBuffers","HexPigmentColors"))assertNull(loader.getResource("com/iridium126/createmanaindustry/client/particles/engine/"+name+".class"));
        for(String name:List.of("hex_reconcile.comp","hex_prepare.comp","hex_pattern.vsh","hex_pattern.fsh","chunks/hex_pattern.glsl","chunks/hex_pattern_state.glsl","chunks/hex_update.glsl"))assertNull(loader.getResource("assets/createmanaindustry/shaders/particles/"+name),name);
        assertNotNull(loader.getResource("assets/createmanaindustry/shaders/particles/hex_shared_prepare.comp"));
        assertEquals(512,com.iridium126.createmanaindustry.client.particles.engine.ParticleBuffers.RADIX_BINS);assertEquals(6,com.iridium126.createmanaindustry.client.particles.engine.ParticleBuffers.INDIRECT_COMMANDS);
        var material=com.iridium126.createmanaindustry.client.particles.emitter.ParticleTypes.Material.PACKAGE;assertEquals(5,material.index());assertEquals(6,com.iridium126.createmanaindustry.client.particles.emitter.ParticleTypes.standard(material).id());
    }
    @Test void uiLayoutChangesDoNotRebuildGeometry(){var layout=new HexUiLayout();var style=new HexShape.Style(10,2.5f,.1f,.2f,.2f,1);
        var points=List.of(new net.minecraft.world.phys.Vec2(100,100),new net.minecraft.world.phys.Vec2(110,100),new net.minecraft.world.phys.Vec2(110,110));assertTrue(layout.update(points,Set.of(),style,true));var shape=layout.shape;
        assertFalse(layout.update(List.of(new net.minecraft.world.phys.Vec2(200,300),new net.minecraft.world.phys.Vec2(220,300),new net.minecraft.world.phys.Vec2(220,320)),Set.of(),style,true));assertSame(shape,layout.shape);assertEquals(20,layout.scale);
        assertTrue(layout.update(points,Set.of(),style,false));shape=layout.shape;
        assertFalse(layout.update(List.of(points.get(0),points.get(1),new net.minecraft.world.phys.Vec2(120,105)),Set.of(),style,false));assertSame(shape,layout.shape);
    }
    @Test void runtimeShaderAndMixinTargetsMatchResolvedDependency()throws Exception{
        String root="com/iridium126/createmanaindustry/mixin/";
        var gui=type("at/petrak/hexcasting/client/gui/GuiSpellcasting");var render=gui.methods.stream().filter(m->m.name.equals("render")).findFirst().orElseThrow();
        int patterns=0,dots=0,flush=-1,lastPattern=-1;
        for(int i=0;i<render.instructions.size();i++)if(render.instructions.get(i) instanceof MethodInsnNode call){
            if(call.owner.equals("at/petrak/hexcasting/client/render/RenderLib")&&call.name.equals("drawPatternFromPoints")){assertEquals("(Lorg/joml/Matrix4f;Ljava/util/List;Ljava/util/Set;ZIIFFFD)V",call.desc);patterns++;lastPattern=i;}
            if(call.owner.equals("at/petrak/hexcasting/client/render/RenderLib")&&call.name.equals("drawSpot")){assertEquals("(Lorg/joml/Matrix4f;Lnet/minecraft/world/phys/Vec2;FFFFF)V",call.desc);dots++;}
            if(call.owner.equals("com/mojang/blaze3d/systems/RenderSystem")&&call.name.equals("enableDepthTest")&&flush<0)flush=i;
        }assertEquals(2,patterns);assertEquals(1,dots);assertTrue(flush>lastPattern,"UI batch would flush after text or before WIP");
        var level=type("net/minecraft/client/renderer/LevelRenderer");assertTrue(level.methods.stream().anyMatch(m->m.name.equals("renderEntity")&&m.desc.equals("(Lnet/minecraft/world/entity/Entity;DDDFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V")));
        var shadow=type("net/irisshaders/iris/shadows/ShadowRenderer");for(String field:List.of("shouldRenderEntities","shouldRenderPlayer","entityFrustumHolder"))assertTrue(shadow.fields.stream().anyMatch(f->f.name.equals(field)));
        var shadowRender=shadow.methods.stream().filter(m->m.name.equals("renderShadows")).findFirst().orElseThrow();int frusta=0,preparedAt=-1,firstEntity=Integer.MAX_VALUE;
        for(int i=0;i<shadowRender.instructions.size();i++)if(shadowRender.instructions.get(i) instanceof MethodInsnNode call){
            if(call.owner.equals("net/minecraft/client/renderer/culling/Frustum")&&call.name.equals("prepare")&&call.desc.equals("(DDD)V")){if(++frusta==2)preparedAt=i;}
            if(call.name.equals("renderEntities")||call.name.equals("renderPlayerEntity"))firstEntity=Math.min(firstEntity,i);
        }assertEquals(2,frusta);assertTrue(preparedAt>=0&&preparedAt<firstEntity,"Shadow takeover must finish before native player rendering");
        var mixin=type(root+"hexcasting/HexPatternRedirectMixin");assertTrue(java.util.Arrays.stream(mixin.methods.get(1).instructions.toArray()).anyMatch(i->i instanceof MethodInsnNode call&&call.name.equals("castingStack")));
    }
    @Test void productionOrbitProducerIsIndependentOfGeneralPool()throws Exception {
        var c=type("com/iridium126/createmanaindustry/client/particles/engine/CMIParticleEngine");var compute=c.methods.stream().filter(m->m.name.equals("runCompute")).findFirst().orElseThrow();
        assertFalse(java.util.Arrays.stream(compute.instructions.toArray()).anyMatch(i->i instanceof MethodInsnNode call&&call.owner.endsWith("HexPatternRuntime")&&call.name.equals("prepare")));
        assertFalse(java.util.Arrays.stream(compute.instructions.toArray()).anyMatch(i->i instanceof MethodInsnNode call&&(call.name.equals("hexReconcile")||call.name.equals("hexPrepare"))));
    }
}
