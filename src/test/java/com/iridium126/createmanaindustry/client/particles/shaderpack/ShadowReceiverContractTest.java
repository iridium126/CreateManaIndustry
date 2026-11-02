package com.iridium126.createmanaindustry.client.particles.shaderpack;

import com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackInventory;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShadowReceiverContractTest {
    @Test void unknownPackCannotAuthorizeMaterialChanges(){
        var contract=ShadowReceiverContract.resolve(ShaderPackInventory.of(Map.of("a.fsh","void main(){}")));
        assertFalse(contract.forward());assertThrows(IllegalStateException.class,()->contract.adapt(new ModelShaderOptimization.Stages("",null,null,null,"")));
    }
    @Test void visibilityRewriteRetainsLightingAndDisabledShadowBranches(){
        var contract=new ShadowReceiverContract("BSL 10.1.1",true,"test");
        String f="vec3 SampleBasicShadow(vec3 p){return vec3(0.);}\nvec3 GetShadow(vec3 p){return SampleBasicShadow(p);}\nfloat GetCloudShadow(vec3 p){return .3;}\nvoid GetLighting(inout vec3 a){a*=GetShadow(vec3(0))*max(dot(vec3(1),vec3(1)),0.);}";
        String adapted=contract.adapt(new ModelShaderOptimization.Stages("v",null,null,null,f)).fragment();
        assertTrue(adapted.contains("GetShadow(vec3 p){ return vec3(1.0); }"));assertTrue(adapted.contains("GetCloudShadow(vec3 p){ return 1.0; }"));
        assertTrue(adapted.contains("max(dot(vec3(1),vec3(1)),0.)"));
        String disabled=f.replace("return SampleBasicShadow(p);","return vec3(p.y);");
        assertTrue(contract.adapt(new ModelShaderOptimization.Stages("v",null,null,null,disabled)).fragment().contains("return vec3(p.y);"));
        assertThrows(IllegalArgumentException.class,()->contract.adapt(new ModelShaderOptimization.Stages("",null,null,null,f.replace("return SampleBasicShadow(p);","imageStore(x,y,z);return SampleBasicShadow(p);"))));
    }
    @Test void allLocalVersionsHaveDistinctFixedIdentities()throws Exception{
        Path packs=Path.of("run/shaderpacks");if(!Files.isDirectory(packs))return;
        int count=0;try(var files=Files.list(packs)){
            for(Path p:files.filter(f->f.toString().endsWith(".zip")).toList()){
                var inventory=ShaderPackInventory.readPack(p);var contract=ShadowReceiverContract.resolve(inventory);
                assertNotEquals("unknown/modified",contract.pack(),p.toString());count++;
                var modified=new java.util.TreeMap<>(inventory.sources());modified.put("cmi_contract_test.fsh","// changed");
                assertFalse(ShadowReceiverContract.resolve(ShaderPackInventory.of(modified)).forward());
            }
        }assertEquals(8,count);
    }
}
