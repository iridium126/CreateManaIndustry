package com.iridium126.createmanaindustry.client.particles.engine.hardware;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.iridium126.createmanaindustry.client.particles.engine.ParticleShaderSource;

class HardwareUpdateSourceTest {
    private String source(){return ParticleShaderSource.loadParticle("update.comp",path->{try{return Files.readString(Path.of("src/main/resources/assets/createmanaindustry",path)).replace("\r\n","\n");}catch(Exception e){throw new IllegalStateException(e);}});}
    @Test void preservesLiveStateAndUsesTheRealLocalQueryBoundary(){
        var variant=HardwareUpdateSource.build(source());var names=new HashSet<String>();int end=12;
        for(var field:variant.fields()){assertTrue(names.add(field.name()),field.name());assertEquals(end,field.offset());char c=field.type().charAt(field.type().length()-1);end+=c>='2'&&c<='4'?c-'0':1;}
        assertTrue(names.containsAll(Set.of("p0","pos","vel","hp","hurt","roll","target","vT","spMaxV","cmiColumnRestored","cmiNavigationApplied","cmiStormMissing")));
        assertEquals(0,variant.strideWords()%4);assertTrue(variant.strideWords()>=end);assertTrue(variant.source().contains("layout(r32ui,binding=7)coherent uniform uimageBuffer"));
        assertFalse(variant.source().contains("r7State.words"));assertTrue(variant.source().contains("floatBitsToUint(separationRadius)"));
        assertTrue(variant.source().contains("if(r7Resume)"));assertTrue(variant.source().contains("floatBitsToUint(readBuf.data[saved*4u].w)"));
        assertTrue(variant.source().contains("++sepVisits > STORM_MAX_VISITS"));
        assertFalse(variant.source().contains("for(uint j=0u;j<min(prevCounter.live,uCapacity);j++)"));
    }
    @Test void allPairsEnumerationRequiresTheExplicitOfflineOracle(){
        String text=source();
        for(boolean fused:new boolean[]{false,true}){
            String runtime=HardwareUpdateSource.build(text,fused).source();
            assertTrue(runtime.contains("++sepVisits > STORM_MAX_VISITS"));
            assertFalse(runtime.contains("for(uint j=0u;j<min(prevCounter.live,uCapacity);j++)"));
        }
        String oracle=HardwareUpdateSource.buildReference(text).source();
        assertTrue(oracle.contains("for(uint j=0u;j<min(prevCounter.live,uCapacity);j++)"));
        assertFalse(oracle.contains("++sepVisits > STORM_MAX_VISITS"));
    }
    @Test void refusesToGuessAfterTheQueryBoundaryChanges(){
        String text=source();assertThrows(IllegalArgumentException.class,()->HardwareUpdateSource.build(text.replace("vec3 sepW = vec3(0.0);","vec3 sepW;")));
        assertThrows(IllegalArgumentException.class,()->HardwareUpdateSource.build(text+"\nvoid main() {"));
    }
}
