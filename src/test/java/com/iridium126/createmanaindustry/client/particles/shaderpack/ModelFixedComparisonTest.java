package com.iridium126.createmanaindustry.client.particles.shaderpack;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ModelFixedComparisonTest {
    static ModelShaderOptimization.Plan optimize(String body){return ModelShaderOptimization.prepare(new ModelShaderOptimization.Stages(null,null,null,null,"#version 400\nuniform int entityId;uniform ivec2 atlasSize;"+body),37,64,64);}
    @Test void foldsOnlyComparisonsAndPreservesArithmeticAndUniforms(){
        var result=optimize("void main(){int n=entityId+1;if(entityId==37&&atlasSize.x<600)gl_FragColor=vec4(n);}");
        assertTrue(result.report().contains("fixed-comparisons"),result.report());assertTrue(result.stages().fragment().contains("uniform int entityId"));
        assertTrue(result.stages().fragment().contains("entityId + 1"));assertTrue(result.stages().fragment().contains("true"));
    }
    @Test void ambiguousBindingsAndNonRepresentableLiteralsAreLeftAlone(){
        var result=optimize("int f(int entityId){return entityId==37?1:0;}void main(){if(entityId==37.0000001lf)gl_FragColor=vec4(f(4));}");
        assertFalse(result.report().contains("fixed-comparisons"),result.report());
        var literal=optimize("void main(){if(entityId==37.0000001lf)gl_FragColor=vec4(1);}");
        assertFalse(literal.report().contains("fixed-comparisons"),literal.report());
    }
    @Test void discardNeverCrossesAnIncrementWithAnUnrelatedDeclarationName(){
        String source="#version 400\nuniform int entityId;uniform sampler2D tex;in vec2 uv;void main(){vec4 c=texture(tex,uv);float scratch=++c.a;if(c.a<.1)discard;gl_FragColor=c;}";
        var result=ModelShaderOptimization.prepare(new ModelShaderOptimization.Stages(null,null,null,null,source),37,64,64);
        assertFalse(result.report().contains("early-alpha(1)"),result.report());
    }
    @Test void mixedUnsignedComparisonsAreNotInterpretedAsSigned(){
        var result=ModelShaderOptimization.prepare(new ModelShaderOptimization.Stages(null,null,null,null,"#version 400\nuniform int entityId;void main(){if(entityId<1u)gl_FragColor=vec4(1);}"),-1,64,64);
        assertFalse(result.report().contains("fixed-comparisons"),result.report());
    }
}
