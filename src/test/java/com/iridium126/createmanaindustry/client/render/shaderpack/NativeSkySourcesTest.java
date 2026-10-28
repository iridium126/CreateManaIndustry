package com.iridium126.createmanaindustry.client.render.shaderpack;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeSkySourcesTest {
    @Test void targetCloudDistanceClipPreservesExtensionOrderAndOriginalOutputs(){
        String source="#version 330 compatibility\n#extension GL_ARB_gpu_shader5 : enable\nlayout(location=0) out vec4 result;void main(){result=vec4(1);}";
        String target=NativeSkySources.targetCloudFragment(source);
        assertTrue(target.indexOf("#extension")<target.indexOf("uniform mat4"));
        assertTrue(target.contains("result=vec4(1)"));assertTrue(target.contains("cmiTargetCloudStock();"));
        assertTrue(target.contains("hit*hit*p.w*p.w"));
    }
    @Test void targetVariantCannotSampleItselfOrWriteSourceImages() {
        String input="uniform sampler2D CmiTargetSky;\n"
                +"bool cmiRayActive(vec3 direction) { if(direction.x>0.0){ return true; } return false; }\n"
                +"vec3 cmiNativeSky(vec3 original,vec3 direction,float gamma,float radianceScale) { return texture(CmiTargetSky,direction.xy).rgb; }\n"
                +"void main(){if(true) imageStore(cache,ivec3(1,2,3),vec4(foo(2.0))); float x=1.0;}";
        String target=NativeSkySources.isolate(input,"photon");
        assertTrue(target.contains("return original;"));assertFalse(target.contains("texture(CmiTargetSky"));
        assertFalse(target.contains("imageStore("));assertTrue(target.contains("if(true) ; float x=1.0;"));
        assertTrue(input.contains("imageStore("));
    }
    @Test void removingWritesPreservesReadCallsAndIndependentStatements() {
        String input="void main(){imageStore(a,ivec2(2),vec4(0)); vec4 y=imageLoad(a,ivec2(3)); imageStore(b,ivec2(4),y);}";
        String target=NativeSkySources.readOnly(input);
        assertTrue(target.contains("imageLoad(a,ivec2(3))"));assertFalse(target.contains("imageStore"));
    }
    @Test void unsupportedSharedStorageFailsBeforePublishingAnyTargetVariant() {
        assertThrows(IllegalArgumentException.class,()->NativeSkySources.readOnly("layout(std430) buffer Cache {float x;}; void main(){x=2.;}"));
        assertThrows(IllegalArgumentException.class,()->NativeSkySources.readOnly("void main(){imageAtomicAdd(sharedCache,ivec2(0),1);}"));
    }
    @Test void targetPreservesCloudCompositionAndAvoidsDoubleCameraEffects() {
        String input="uniform int isEyeInWater; uniform float blindness; void main(){vec3 color=vec3(1.); vec4 vl=vec4(1.);float cloudAlpha=1.;color *= vl.a * cloudAlpha; color += vl.rgb; color*=blindness;}";
        String target=NativeSkySources.isolate(input,"bliss");
        assertTrue(target.contains("cmiTargetEyeInWater"));assertFalse(target.contains("uniform float blindness"));
        assertTrue(target.indexOf("return;")>target.indexOf("color += vl.rgb;"));
        assertTrue(target.indexOf("return;")<target.indexOf("color*=blindness"));
    }
}
