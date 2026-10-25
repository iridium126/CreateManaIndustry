package com.iridium126.createmanaindustry.client.render.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ShaderPatchPlanTest {
    static Map<String,String> photon(int n){
        var sources=new HashMap<String,String>();
        var colors=new ArrayList<String>();for(int i=0;i<n;i++)colors.add("vec3("+(i==18?4:i)+".0) * 8.0");
        sources.put("include/lighting/lpv/light_colors.glsl","const vec3["+n+"] light_color = vec3["+n+"]("+String.join(",\n",colors)+");");
        sources.put("include/lighting/lpv/floodfill.glsl","bool is_emitter(uint block_id){return 32u <= block_id && block_id < "+(32+n)+"u;}");
        sources.put("program/lpv/setup.csh","layout(local_size_x = "+n+") in; void main(){ vec3 color=light_color[clamp(index,0u,"+(n-1)+"u)]; }");
        sources.put("shaders.properties","image.light_data_img = light_data_sampler rgba rgba16f half_float false false "+n+" 2\n");
        sources.put("block.properties","block.10050 = candle:lit=true\nblock.10036 = torch wall_torch\n");
        sources.put("include/vertex/utility.glsl","uint id(){ return uint(max0(mc_Entity.x - 10000.0)); }");return sources;
    }
    @Test void variableTableSizesAndEqualSlotContract(){
        for(int n:List.of(32,48,64)){
            var inv=ShaderPackInventory.of(photon(n));var plan=ShaderPatchPlan.prepare(inv);
            assertTrue(plan.enabled(),plan.reason());assertEquals(10050,plan.blockId());assertEquals(10036,plan.replacementId());
            assertTrue(plan.edits().get("include/lighting/lpv/light_colors.glsl").contains("vec3(1.00, 0.35, 0.55)"));
            assertEquals(PackCapabilities.WorldPath.BASELINE,PackCapabilities.detect(inv,plan).worldPath());
        }
    }
    @Test void materialEncodingOffsetIsDerivedFromVertexCode(){
        var input=photon(48);input.put("block.properties",input.get("block.properties").replace("10050","20050").replace("10036","20036"));
        input.put("include/vertex/utility.glsl",input.get("include/vertex/utility.glsl").replace("10000","20000"));
        var plan=ShaderPatchPlan.prepare(ShaderPackInventory.of(input));assertTrue(plan.enabled(),plan.reason());assertEquals(20050,plan.blockId());assertEquals(20036,plan.replacementId());
    }
    @Test void everyNecessaryFailureLeavesAllEditsAndMappingsDisabled(){
        for(String missing:List.of("include/lighting/lpv/light_colors.glsl","program/lpv/setup.csh","shaders.properties","block.properties")){
            var inputs=photon(48);inputs.remove(missing);var plan=ShaderPatchPlan.prepare(ShaderPackInventory.of(inputs));assertFalse(plan.enabled());assertTrue(plan.edits().isEmpty());assertEquals(-1,plan.blockId());
        }
        var inputs=photon(48);inputs.put("program/lpv/setup.csh",inputs.get("program/lpv/setup.csh").replace("47u","31u"));
        assertFalse(ShaderPatchPlan.prepare(ShaderPackInventory.of(inputs)).enabled());
        inputs=photon(48);inputs.put("include/lighting/lpv/light_colors.glsl",inputs.get("include/lighting/lpv/light_colors.glsl").replace("vec3(4.0)","vec3(5.0)").replaceFirst("vec3\\(5.0\\)","vec3(6.0)"));
        assertFalse(ShaderPatchPlan.prepare(ShaderPackInventory.of(inputs)).enabled());
    }
    @Test void commentsWhitespaceNamesAndFullPathsDoNotSelectAnotherFile(){
        var inputs=photon(48);String path="include/lighting/lpv/light_colors.glsl";
        inputs.put(path,"// fake const vec3[32] light_color\n"+inputs.get(path).replace("const vec3[","const /* comment */ vec3 [ ").replace("light_color =","light_color /*between*/ ="));
        inputs.put("unrelated/light_colors.glsl","do not modify");
        var plan=ShaderPatchPlan.prepare(ShaderPackInventory.of(inputs));assertTrue(plan.enabled(),plan.reason());
        assertEquals("do not modify",plan.transform("unrelated/light_colors.glsl","do not modify"));
        assertThrows(IllegalStateException.class,()->plan.transform(path,"new same-name content"));
    }
    @Test void ambiguityDoesNotPickTheFirstDetector(){
        var inputs=photon(48);inputs.put("lib/voxelization/lightVoxelization.glsl","int GetVoxelIDs(int mat){return 1;}");
        var plan=ShaderPatchPlan.prepare(ShaderPackInventory.of(inputs));assertFalse(plan.enabled());assertTrue(plan.reason().contains("ambiguous"));
    }
    @Test void inventoriesCaptureHighBuffersDirectivesAndSameNameUpdates(@TempDir Path root)throws Exception{
        Files.createDirectories(root.resolve("program"));Path file=root.resolve("program/test.fsh");
        Files.writeString(file,"/* RENDERTARGETS: 0,18,19 */\nuniform sampler2D colortex16; // colortex31\n");
        var a=ShaderPackInventory.read(root);assertEquals(Set.of(0,16,18,19),a.colorBuffers());
        Files.writeString(file,"/* DRAWBUFFERS: 07 */\nuniform sampler2D colortex17;\n");
        var b=ShaderPackInventory.read(root);assertNotEquals(a.digest(),b.digest());assertEquals(Set.of(0,7,17),b.colorBuffers());
    }
    @Test void freshVoxelIdsAvoidCollisions(){
        var inv=ShaderPackInventory.of(Map.of("lib/voxelization/lightVoxelization.glsl","int GetVoxelIDs(int mat){return 1;}","lib/colors/blocklightColors.glsl","vec4 GetColor(int mat){if(mat==98)return vec4(0);return vec4(blocklightCol * 20.0, 0.0);}","block.properties","block.11000=stone\n"));
        var plan=ShaderPatchPlan.prepare(inv);assertTrue(plan.enabled(),plan.reason());assertEquals(11004,plan.blockId());assertTrue(plan.edits().values().stream().anyMatch(s->s.contains("mat == 99")));
    }
    @Test void libraryStageExtensionsParticipateInInventoryAndDigest(@TempDir Path root)throws Exception{
        for(String extension:List.of("frag","vert","geom","comp","inc","h"))Files.writeString(root.resolve("library."+extension),"uniform sampler2D colortex20;\n");
        var before=ShaderPackInventory.read(root);assertEquals(6,before.sources().size());assertEquals(Set.of(20),before.colorBuffers());
        Files.writeString(root.resolve("library.frag"),"uniform sampler2D colortex21;\n");
        var after=ShaderPackInventory.read(root);assertNotEquals(before.digest(),after.digest());assertEquals(Set.of(20,21),after.colorBuffers());
    }
}
