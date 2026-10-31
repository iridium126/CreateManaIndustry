package com.iridium126.createmanaindustry.client.particles.shaderpack;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FeatherShaderOptimizationTest {
    private static final String V="#version 400\nuniform int cmi_FeatherPass;void main(){if(cmi_FeatherPass!=0)gl_Position=vec4(1);else gl_Position=vec4(2);}";
    private static final String F="#version 400\nin vec4 entityColor;uniform sampler2D tex;in vec2 uv;void main(){vec4 c=texture(tex,uv);if(entityColor.a<.001)c.r+=.01;c.rgb=mix(c.rgb,entityColor.rgb,entityColor.a);if(c.a<.1)discard;gl_FragColor=c;}";
    @Test void featherKeepsNativeSamplingAlphaAndEncoding(){
        var plan=ModelShaderOptimization.prepareFeatherBranches(new ModelShaderOptimization.Stages(V,null,null,null,F),true);
        assertEquals(V,plan.stages().vertex());
        assertTrue(plan.stages().fragment().contains("uniform int cmi_FeatherPass"));
        assertTrue(plan.stages().fragment().contains("if (cmi_FeatherPass != 0)"));
        assertTrue(plan.stages().fragment().contains("mix(c.rgb, entityColor.rgb, entityColor.a)"));
        // Transformer 2 prints explicit float suffixes; Iris' embedded 3 omits them.
        assertTrue(plan.stages().fragment().replace("0.0f","0.0").contains("mix(c.rgb, vec3(0.0), 0.0)"),plan.stages().fragment());
        assertTrue(plan.stages().fragment().contains("texture(tex, uv)"));
        assertTrue(plan.stages().fragment().contains("discard"));
        assertTrue(plan.stages().fragment().contains("gl_FragColor = c"));
    }
    @Test void nonExactOrGeometryInterfacesKeepTheirOverlay(){
        for(var input:new ModelShaderOptimization.Stages[]{new ModelShaderOptimization.Stages(V,"geometry",null,null,F),new ModelShaderOptimization.Stages(V,null,"control",null,F),new ModelShaderOptimization.Stages(V,null,null,"eval",F)}){
            var plan=ModelShaderOptimization.prepareFeatherBranches(input,true);assertEquals(F,plan.stages().fragment());assertEquals(input.geometry(),plan.stages().geometry());assertEquals(input.tessControl(),plan.stages().tessControl());assertEquals(input.tessEval(),plan.stages().tessEval());
        }
        assertEquals(F,ModelShaderOptimization.prepareFeatherBranches(new ModelShaderOptimization.Stages(V,null,null,null,F),false).stages().fragment());
    }
    @Test void batchClonesContextsWithoutLeakingMutations(){
        var raw=new ModelShaderOptimization.Stages("#version 400\nuniform int entityId;void main(){gl_Position=vec4(entityId);}",null,null,null,F);
        var expected=ModelShaderOptimization.prepare(raw,37,64,64);
        try(var batch=ModelShaderOptimization.batch()){
            assertEquals(expected,ModelShaderOptimization.prepare(raw,37,64,64));
            var neutral=ModelShaderOptimization.prepare(raw,0,64,64);assertTrue(neutral.stages().vertex().contains("entityId = 0"));
            ModelShaderOptimization.prepareFeatherBranches(expected.stages(),true);
            assertEquals(expected,ModelShaderOptimization.prepare(raw,37,64,64));
            try(var nested=ModelShaderOptimization.batch()){assertEquals(expected,ModelShaderOptimization.prepare(raw,37,64,64));}
            assertEquals(expected,ModelShaderOptimization.prepare(raw,37,64,64));
        }
        assertEquals(expected,ModelShaderOptimization.prepare(raw,37,64,64));
    }
    @Test void arithmeticOnlyOverlayKeepsSharedFragment(){
        String source=F.replace("if(entityColor.a<.001)c.r+=.01;","");
        assertEquals(source,ModelShaderOptimization.prepareFeatherBranches(new ModelShaderOptimization.Stages(V,null,null,null,source),true).stages().fragment());
    }
    @Test void unknownBindingsAndConditionsRemainByteIdentical(){
        for(String source:new String[]{
                F.replace("void main(){","uniform int cmi_FeatherPass;void main(){"),
                F.replace("void main(){","void helper(vec4 entityColor){}void main(){"),
                F.replace("entityColor.a<.001","entityColor.a<texture(tex,uv).a"),
                F.replace("entityColor.a<.001","entityColor.a<.001&&uv.x>0.0")}){
            assertEquals(source,ModelShaderOptimization.prepareFeatherBranches(new ModelShaderOptimization.Stages(V,null,null,null,source),true).stages().fragment());
        }
    }
    @Test void comparisonRemainsGlslAndSamplingStaysBeforeGuard(){
        String source=F.replace("entityColor.a<.001","entityColor.a<1e-40");
        var plan=ModelShaderOptimization.prepareFeatherBranches(new ModelShaderOptimization.Stages(V,null,null,null,source),true);
        String result=plan.stages().fragment();
        assertFalse(plan.applied().isEmpty());
        assertTrue(result.replace("0.0f","0.0").contains("0.0 <"),result);
        assertTrue(result.indexOf("texture(tex, uv)")<result.indexOf("if (cmi_FeatherPass"));
        assertTrue(result.indexOf("discard")>result.indexOf("if (cmi_FeatherPass"));
    }
}
