package com.iridium126.createmanaindustry.client.render.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeSceneContractTest {
    static Map<String,String> layered(){return new HashMap<>(Map.of(
            "world0/composite1.fsh","#include \"/program/c1_blend_layers.fsh\"",
            "include/global.glsl","// settings",
            "program/c1_blend_layers.fsh","uniform sampler2D colortex0; void main(){fragment_color = texture(colortex0, uv).rgb; fragment_color = blend_layers_with_fog(fragment_color);}"));}
    @Test void nativeSceneIsStructuralAndRejectsChangedReaders(){
        var sources=layered();var inventory=ShaderPackInventory.of(sources);var caps=PackCapabilities.detect(inventory,ShaderPatchPlan.prepare(inventory));
        assertEquals(PackCapabilities.WorldPath.SCENE,caps.worldPath());assertEquals(0,caps.sceneBuffer());
        var contract=caps.nativeScene();assertTrue(contract.acceptsActiveSource(sources.get(contract.readerPath())));
        assertFalse(contract.acceptsActiveSource(sources.get(contract.readerPath()).replace("sampler2D","sampler3D")));
        sources.put(contract.readerPath(),sources.get(contract.readerPath()).replace("blend_layers_with_fog","other_function"));
        assertTrue(NativeSceneContract.detect(ShaderPackInventory.of(sources)).isEmpty());
    }
    @Test void threeDimensionalNoiseDoesNotReplaceTheTwoDimensionalScene(){
        var sources=layered();sources.put("shaders.properties","texture.composite.colortex0 = clouds.dat TEXTURE_3D R8 64 64 64 RED UNSIGNED_BYTE\n");
        assertEquals(1,NativeSceneContract.detect(ShaderPackInventory.of(sources)).size());
        sources.put("shaders.properties","texture.composite.colortex0 = image.png\n");
        assertTrue(NativeSceneContract.detect(ShaderPackInventory.of(sources)).isEmpty());
    }
    @Test void conflictingFamiliesNeverSelectByRegistrationOrder(){
        var sources=layered();sources.put("program/composite1.glsl","vec3 color=texelFetch(colortex0,uv,0).rgb; color=pow(color,vec3(2.2));");sources.put("program/composite.glsl","// companion");
        var inventory=ShaderPackInventory.of(sources);assertEquals(PackCapabilities.WorldPath.BASELINE,PackCapabilities.detect(inventory,ShaderPatchPlan.prepare(inventory)).worldPath());
    }
    @Test void alphaBlendEncodingUsesEffectiveOption(){
        var contract=new NativeSceneContract("alpha",0,0,"test","","",2,true,false);
        assertEquals(2,contract.gammaForAlphaBlend(0));assertEquals(1,contract.gammaForAlphaBlend(1));
    }
    @Test void includesWithArbitrarySuffixAndCyclesAreInventoried(@TempDir Path root)throws Exception{
        Files.writeString(root.resolve("composite.fsh"),"#include \"shaders.settings\"\n");
        Files.writeString(root.resolve("shaders.settings"),"#include \"composite.fsh\"\n#define LIGHT 1\n");
        var first=ShaderPackInventory.read(root);assertEquals(2,first.sources().size());
        Files.writeString(root.resolve("shaders.settings"),"#define LIGHT 2\n");assertNotEquals(first.digest(),ShaderPackInventory.read(root).digest());
    }
}
