package com.iridium126.createmanaindustry.client.particles.shaderpack;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShadowVertexContextTest {
    private static final String INPUT="uniform bool cmi_ShadowPass;\nuniform int cmi_LodPass;\nuniform int cmi_FeatherPass,cmi_FeatherWordBase;\nvoid main(){float untouched=sin(1.);}";
    @Test void fixesOnlyOwnedPassDeclarations(){
        var result=ShadowVertexContext.prepare(INPUT,"void main(){}",null,null,null,"void main(){}");
        assertTrue(result.applied());assertTrue(result.source().contains("const bool cmi_ShadowPass=true;"));
        assertTrue(result.source().contains("const int cmi_LodPass=0;"));assertTrue(result.source().contains("uniform int cmi_FeatherWordBase;"));
        assertTrue(result.source().endsWith("void main(){float untouched=sin(1.);}"));
    }
    @Test void retainsUnprovenAuxiliaryAndStorageStages(){
        for(String stage:new String[]{"imageStore(a,p,v);","atomicAdd(a,1);","layout(std430)buffer X{uint x;};"}){
            var result=ShadowVertexContext.prepare(INPUT,stage,null,null,null,"");assertFalse(result.applied());assertSame(INPUT,result.source());
        }
        assertFalse(ShadowVertexContext.prepare(INPUT,"","geometry",null,null,"").applied());
        assertFalse(ShadowVertexContext.prepare(INPUT.replace("bool cmi_ShadowPass","int cmi_ShadowPass"),"",null,null,null,"").applied());
        assertTrue(ShadowVertexContext.prepare(INPUT,"// imageStore()\nvoid main(){}",null,null,null,"").applied());
    }
}
