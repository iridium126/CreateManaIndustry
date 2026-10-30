package com.iridium126.createmanaindustry.client.particles.shaderpack;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ModelShaderOptimizationTest {
    private ModelShaderOptimization.Plan plan(String source) {
        return ModelShaderOptimization.prepare(new ModelShaderOptimization.Stages(null,null,null,null,source),37,64,64);
    }
    @Test void constantsPreserveOtherUniformsScopesAndResourceInstructions() {
        var result=plan("#version 400 compatibility\n/* DRAWBUFFERS:036 */\n"
                +"uniform int entityId, other; uniform ivec2 atlasSize; uniform vec4 entityColor;"
                +"int f(int entityId){return entityId;} int material(){return entityId+atlasSize.x;} void main(){gl_FragData[0]=vec4(material()+other+f(9));}");
        String f=result.stages().fragment();
        assertTrue(f.contains("const int entityId = 37"),result.report());
        assertTrue(f.contains("uniform int other"));assertTrue(f.contains("int f(int entityId)"));
        assertTrue(f.contains("uniform vec4 entityColor"));assertTrue(f.contains("/* DRAWBUFFERS:036 */"));
        assertTrue(f.contains("uniform ivec2 atlasSize"));
    }
    @Test void unknownSourceAndNonUniformDeclarationsStayIdentical() {
        for(String f:new String[]{"not valid shader entityId", "#version 400\nconst int entityId=9;void main(){}",
                "#version 400\nuniform float entityId;void main(){}", "#version 400\nuniform int entityId[3];void main(){}"})
            assertEquals(f,plan(f).stages().fragment());
    }
    @Test void allStagesUseTheSameContextAndCarrierIsNeutral() {
        String source="#version 400\nuniform int entityId; int material(){return entityId;} void main(){int id=material();}";
        var stages=new ModelShaderOptimization.Stages(source,source,source,source,source);
        var body=ModelShaderOptimization.prepare(stages,-1,64,64);
        var item=ModelShaderOptimization.prepare(stages,0,64,64);
        assertEquals(5,body.applied().size());assertEquals(5,item.applied().size());
        assertTrue(body.stages().geometry().contains("-1"));assertTrue(item.stages().fragment().contains("= 0"));
        assertEquals(1,item.skipped().size());
    }
    @Test void packedOutputAndDerivativeSensitiveAlphaAreNeverGuessed() {
        String source="#version 400\nuniform int entityId;uniform sampler2D tex;in vec2 uv;"
                +"void main(){vec4 c=texture(tex,uv);gl_FragData[0]=vec4(c.rgb,fract(c.a*.5));}";
        var result=plan(source);assertTrue(result.skipped().getFirst().contains("early-alpha"));
        assertFalse(result.stages().fragment().contains("discard"));
    }
    @Test void nativeDiscardMovesAcrossOnlyIndependentPureLocalAlu(){
        String base="#version 400\nuniform int entityId;uniform sampler2D tex;in vec2 uv;void main(){vec4 c=texture(tex,uv);%s if(c.a<.1){discard;}gl_FragColor=c;}";
        var safe=plan(base.formatted("vec3 light=normalize(vec3(1));"));
        String shader=safe.stages().fragment();assertTrue(safe.report().contains("early-alpha(1)"),safe.report());
        assertTrue(shader.indexOf("discard")<shader.indexOf("vec3 light"));
        for(String barrier:new String[]{"c.a=.8;","vec4 other=texture(tex,uv);","float edge=dFdx(c.a);","float a=++c.a;","float a=(c.a=.8);"}){
            var p=plan(base.formatted(barrier));assertFalse(p.report().contains("early-alpha(1)"),barrier+" "+p.report());
        }
    }
}
