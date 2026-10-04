layout(location=0) in vec3 aPosition;
layout(location=1) in vec2 aUv;
layout(location=2) in vec3 aNormal;
layout(location=3) in vec4 aColor;
layout(location=4) in uvec2 aInstance;
layout(std430,binding=0) readonly buffer Pool { vec4 pool[]; };
layout(std430,binding=6) readonly buffer Attachment { vec4 attachment[]; };
layout(std430,binding=7) readonly buffer SampledLight { uint sampledLight[]; };
uniform mat4 ModelViewMat,ProjMat;
uniform vec3 uCamPos;
uniform float uPartialTick;
uniform float uChainPartialTick;
uniform bool uSeparateChainInterpolation;
uniform sampler2D uLightmap;
uniform int uLightingMode,uConstantAmbient;
uniform int uSampledLighting;
uniform vec3 uLight0,uLight1;
out vec2 vUv;
out vec4 vColor;
flat out uint vCutout;
#pragma cmi_include packages/vertex_pose.glsl
void main() {
    uint s=aInstance.x,p=4u*s,i=aInstance.y>>1u;
    float partialTick=uSeparateChainInterpolation&&((floatBitsToUint(pool[p+2u].w)>>24u)&1u)!=0u?uChainPartialTick:uPartialTick;
    PackageVertexPose pose;
    if(((floatBitsToUint(pool[p+2u].w)>>24u)&8u)!=0u) {
        uint a=2u*uint(attachment.length()/11)+9u*i;
        pose=packageFramedVertexPose(aPosition,aNormal,aInstance,partialTick,
            pool[p],pool[p+1u],pool[p+2u],pool[p+3u],attachment[2u*i],attachment[2u*i+1u],
            attachment[a],attachment[a+1u],attachment[a+2u],attachment[a+6u],attachment[a+7u],attachment[a+8u]);
    }else pose=packageVertexPose(aPosition,aNormal,aInstance,partialTick,
            pool[p],pool[p+1u],pool[p+2u],pool[p+3u],attachment[2u*i],attachment[2u*i+1u]);
    uint packedLight=pose.packedLight,flags=pose.flags;
    if(uSampledLighting!=0)packedLight=sampledLight[i];
    vCutout=flags&1u;
    vec3 normal=pose.normal;
    // Flywheel uses chunk diffuse for both free and chain package models.
    // Unshaded quads use a zero normal in the cache.
    vec3 n2=normal*normal;
    bool unshaded=dot(n2,n2)<1e-8;
    float shade;
    if(uLightingMode==1) {
        float yFactor=uConstantAmbient!=0?.9:(3.0+normal.y)*.25;
        shade=unshaded?(uConstantAmbient!=0?.9:1.0):min(n2.x*.6+n2.z*.8+n2.y*yFactor,1.0);
    } else {
        vec3 n=unshaded?vec3(0,1,0):mat3(ModelViewMat)*normal;
        shade=min(1.0,(max(dot(uLight0,n),0.0)+max(dot(uLight1,n),0.0))*.6+.4);
    }
    ivec2 light=ivec2(int((packedLight>>4u)&15u),int((packedLight>>20u)&15u));
    vColor=aColor*vec4(texelFetch(uLightmap,light,0).rgb*shade,1);
    vUv=aUv;
    gl_Position=ProjMat*ModelViewMat*vec4(pose.position-uCamPos,1);
}
