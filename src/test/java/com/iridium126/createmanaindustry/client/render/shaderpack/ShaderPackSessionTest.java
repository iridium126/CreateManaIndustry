package com.iridium126.createmanaindustry.client.render.shaderpack;

import org.junit.jupiter.api.Test;
import net.irisshaders.iris.shaderpack.materialmap.*;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ShaderPackSessionTest {
    static BlockEntry block(String name){return new BlockEntry(new NamespacedId("minecraft",name),Map.of("lit","true"));}
    @Test void candidateMappingIsCopyOnSuccessAndUnchangedOnFailure(){
        var session=ShaderPackSession.candidate(ShaderPackInventory.of(ShaderPatchPlanTest.photon(48)));
        var blocks=new Int2ObjectLinkedOpenHashMap<List<BlockEntry>>();blocks.put(10050,List.of(block("candle")));blocks.put(10036,List.of(block("torch")));
        var items=new Object2IntOpenHashMap<NamespacedId>();var candle=new NamespacedId("minecraft","candle");items.put(candle,10050);
        var result=session.apply(new BlockIdMapContext(blocks,items));
        assertEquals(10050,items.getInt(candle));assertEquals(1,blocks.get(10036).size());assertEquals("candle",blocks.get(10050).getFirst().id().getName());
        assertEquals(10036,result.itemIds().getInt(candle));assertEquals(2,result.blockProperties().get(10036).size());assertEquals("molten_salt_fuel_tank",result.blockProperties().get(10050).getFirst().id().getName());
        blocks.remove(10036);assertThrows(IllegalStateException.class,()->session.apply(new BlockIdMapContext(blocks,items)));
        assertEquals("candle",blocks.get(10050).getFirst().id().getName());assertEquals(10050,items.getInt(candle));
    }
    @Test void sameSourcesNewLoadAndTargetChangesInvalidateGeneration(){
        var inv=ShaderPackInventory.of(ShaderPatchPlanTest.photon(48));var a=ShaderPackSession.candidate(inv);var b=ShaderPackSession.candidate(inv);
        assertNotEquals(a.generation(),b.generation());long revision=a.revision();Object pipeline=new Object();
        a.observe(pipeline,100,100);assertNotEquals(revision,a.revision());revision=a.revision();a.observe(pipeline,100,100);assertEquals(revision,a.revision());
        a.observe(pipeline,200,100);assertNotEquals(revision,a.revision());
    }
    @Test void disabledPlanNeverRewritesNativeMappings(){
        var session=ShaderPackSession.candidate(ShaderPackInventory.of(Map.of()));var context=new BlockIdMapContext(new Int2ObjectLinkedOpenHashMap<>(),new Object2IntOpenHashMap<>());
        assertSame(context,session.apply(context));assertFalse(session.requestUnmodifiedRetry());
    }
    @Test void sessionInitializationDoesNotRequireIrisClasses()throws Exception{
        ClassLoader parent=getClass().getClassLoader();String sessionName=ShaderPackSession.class.getName();
        var isolated=new ClassLoader(parent){
            @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{
                if(name.startsWith("net.irisshaders."))throw new ClassNotFoundException("Iris intentionally absent");
                if(name.equals(sessionName)){synchronized(getClassLoadingLock(name)){Class<?> type=findLoadedClass(name);if(type==null)try(var in=parent.getResourceAsStream(name.replace('.','/')+".class")){byte[] bytes=java.util.Objects.requireNonNull(in).readAllBytes();type=defineClass(name,bytes,0,bytes.length);}catch(java.io.IOException e){throw new ClassNotFoundException(name,e);}if(resolve)resolveClass(type);return type;}}
                return super.loadClass(name,resolve);
            }
        };
        assertNotNull(Class.forName(sessionName,true,isolated));
    }
}
