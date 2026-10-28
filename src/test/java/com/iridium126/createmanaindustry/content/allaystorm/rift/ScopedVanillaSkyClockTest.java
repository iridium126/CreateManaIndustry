package com.iridium126.createmanaindustry.content.allaystorm.rift;

import com.iridium126.createmanaindustry.client.allaystorm.rift.SkyRenderScope;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelData;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.lang.reflect.Proxy;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the shipped mixin method on a minimal Level, with real vanilla
 * getTimeOfDay/moon/sun queries. No client, server, chunks or GL are started. */
class ScopedVanillaSkyClockTest {
    private static int names;
    @org.junit.jupiter.api.BeforeAll static void bootstrap(){
        if(net.neoforged.fml.loading.LoadingModList.get()==null)net.neoforged.fml.loading.LoadingModList.of(java.util.List.of(),java.util.List.of(),java.util.List.of(),java.util.List.of(),java.util.Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
    }
    private static Level fixture(boolean patched,boolean client,AtomicLong source)throws Exception {
        String name="cmi/validation/SkyClock"+(names++);var writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21,Opcodes.ACC_PUBLIC,name,null,"net/minecraft/world/level/Level",new String[]{"net/minecraft/world/level/LevelAccessor"});
        writer.visitField(Opcodes.ACC_PUBLIC,"data","Lnet/minecraft/world/level/storage/LevelData;",null,null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC,"type","Lnet/minecraft/world/level/dimension/DimensionType;",null,null).visitEnd();
        for(String[] getter:new String[][]{{"getLevelData","data","net/minecraft/world/level/storage/LevelData"},{"dimensionType","type","net/minecraft/world/level/dimension/DimensionType"}}){
            var method=writer.visitMethod(Opcodes.ACC_PUBLIC,getter[0],"()L"+getter[2]+";",null,null);method.visitCode();method.visitVarInsn(Opcodes.ALOAD,0);method.visitFieldInsn(Opcodes.GETFIELD,name,getter[1],"L"+getter[2]+";");method.visitInsn(Opcodes.ARETURN);method.visitMaxs(0,0);method.visitEnd();
        }
        // Both old and repaired worlds expose the scoped getDayTime() hook.
        var get=writer.visitMethod(Opcodes.ACC_PUBLIC,"getDayTime","()J",null,null);get.visitCode();get.visitMethodInsn(Opcodes.INVOKESTATIC,Type.getInternalName(SkyRenderScope.class),"dayTime","()J",false);get.visitInsn(Opcodes.LRETURN);get.visitMaxs(0,0);get.visitEnd();
        if(patched){
            var node=new ClassNode();try(var input=ScopedVanillaSkyClockTest.class.getClassLoader().getResourceAsStream("com/iridium126/createmanaindustry/mixin/allaystorm/AllayScopedSkyTimeMixin.class")){new ClassReader(input.readAllBytes()).accept(node,0);}
            var method=node.methods.stream().filter(m->m.name.equals("dayTime")&&m.desc.equals("()J")).findFirst().orElseThrow();method.accept(writer);
        }
        writer.visitEnd();byte[] bytes=writer.toByteArray();
        var loader=new ClassLoader(ScopedVanillaSkyClockTest.class.getClassLoader()){Class<?> define(){return defineClass(name.replace('/','.'),bytes,0,bytes.length);}};
        var unsafeField=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");unsafeField.setAccessible(true);var unsafe=(sun.misc.Unsafe)unsafeField.get(null);
        var level=(Level)unsafe.allocateInstance(loader.define());var clientField=Level.class.getDeclaredField("isClientSide");clientField.setAccessible(true);clientField.set(level,client);
        LevelData data=(LevelData)Proxy.newProxyInstance(loader,new Class<?>[]{LevelData.class},(proxy,method,args)->switch(method.getName()){
            case "getDayTime" -> source.get();case "getGameTime" -> 123456L;default -> throw new AssertionError("unexpected world data query/mutation "+method.getName());
        });
        level.getClass().getField("data").set(level,data);
        var type=new DimensionType(OptionalLong.empty(),true,false,false,true,1,true,false,-64,384,384,null,null,0,null);
        level.getClass().getField("type").set(level,type);return level;
    }
    @Test void oldGetterHookLeavesVanillaSkyIdenticalToTheSource()throws Exception {
        var day=new AtomicLong(6000);var old=fixture(false,true,day);
        try {SkyRenderScope.enter(new AllaySkyContext(0,-6000,0,0,0,1,true),0);
            assertEquals(-6000,old.getDayTime());assertEquals(6000,old.dayTime());
            assertEquals(old.dimensionType().timeOfDay(6000),old.getTimeOfDay(0));
            assertNotEquals(old.dimensionType().timeOfDay(-6000),old.getTimeOfDay(0));
        }finally{SkyRenderScope.leave();}
    }
    @Test void shippedClockOverrideFeedsAllVanillaCelestialQueriesAndRestoresSource()throws Exception {
        var day=new AtomicLong(6000);var client=fixture(true,true,day);var server=fixture(true,false,day);
        for(long source:new long[]{0,6000,12000,18000,-6000,24000,1000000000})for(boolean cycle:new boolean[]{false,true})for(float rate:new float[]{0,.5f,1,2}){
            day.set(source);long target=source-12000;
            assertEquals(source,client.dayTime());assertEquals(123456,client.getLevelData().getGameTime());
            try{SkyRenderScope.enter(new AllaySkyContext(0,target,0,0,0,1,cycle,.25f,rate),5);
                long expected=target+(cycle?(long)Math.floor(.25+5*rate):0);
                assertEquals(expected,client.dayTime());assertEquals(source,server.dayTime());
                assertEquals(client.dimensionType().timeOfDay(expected),client.getTimeOfDay(.8f));
                assertEquals(client.getTimeOfDay(.8f)*(float)(Math.PI*2),client.getSunAngle(.8f));
                assertEquals(client.dimensionType().moonPhase(expected),client.getMoonPhase());
                assertEquals(DimensionType.MOON_BRIGHTNESS_PER_PHASE[client.getMoonPhase()],client.getMoonBrightness());
                assertEquals(source,day.get());assertEquals(123456,client.getLevelData().getGameTime());
            }finally{SkyRenderScope.leave();}
            assertEquals(source,client.dayTime());assertEquals(client.dimensionType().timeOfDay(source),client.getTimeOfDay(0));
        }
    }
    @Test void installedLevelUsesTheInheritedClockThatTheMixinOverrides()throws Exception {
        var read=new RiftMixinAbiTest();var level=read.read("net/minecraft/world/level/Level");assertTrue(level.methods.stream().noneMatch(m->m.name.equals("dayTime")&&m.desc.equals("()J")),"re-evaluate the override when Level declares its own clock");
        assertTrue(level.interfaces.contains("net/minecraft/world/level/LevelAccessor"));
        var clock=read.read("net/minecraft/world/level/LevelTimeAccess").methods.stream().filter(m->m.name.equals("getTimeOfDay")).findFirst().orElseThrow();
        assertTrue(java.util.Arrays.stream(clock.instructions.toArray()).anyMatch(i->i instanceof MethodInsnNode m&&m.name.equals("dayTime")));
    }
}
