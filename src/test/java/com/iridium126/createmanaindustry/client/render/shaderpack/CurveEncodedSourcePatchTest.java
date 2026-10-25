package com.iridium126.createmanaindustry.client.render.shaderpack;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CurveEncodedSourcePatchTest {
    @Test void layerMergeKeepsMainClosingBraceOutsideLineComment(){
        String input="#version 330 core\nlayout(location = 0) out vec4 compositeOutput1;\nvoid main(){\nif(true){compositeOutput1=vec4(0);}\nelse {\nvec3 finalComposite=vec3(0);\ncompositeOutput1 = vec4(finalComposite, 0.0);\n}\n}\n";
        for(String newline:new String[]{"\n","\r\n"}){
            String output=new CurveEncodedSourcePatch(7110,29,9,10).transformShader("composite.fsh",input.replace("\n",newline));
            assertNotNull(output);
            assertEquals(0,braceBalance(ShaderPackInventory.code(output)));
            String broken=output.replace("// [CMI] layer merge — end"+newline,"// [CMI] layer merge — end");
            assertEquals(1,braceBalance(ShaderPackInventory.code(broken)),"the original missing-newline bug hides main's closing brace");
        }
    }
    private static int braceBalance(String code){int balance=0;for(char c:code.toCharArray()){if(c=='{')balance++;if(c=='}')balance--;}return balance;}
    @Test void shadowHolderClearsOnlyItsOwningRenderer(){
        Object first=new Object(),other=new Object();
        IrisShadowTextures.setShadowTargets(first);IrisShadowTextures.setShadowDepthTextureId(123);
        IrisShadowTextures.clearIfOwned(other);assertEquals(123,IrisShadowTextures.getShadowDepthTextureId());
        IrisShadowTextures.clearIfOwned(first);assertEquals(-1,IrisShadowTextures.getShadowDepthTextureId());assertNull(IrisShadowTextures.getShadowTargets());
    }
}
