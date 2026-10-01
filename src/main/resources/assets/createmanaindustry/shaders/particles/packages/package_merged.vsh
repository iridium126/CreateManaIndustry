// No SSBO bindings: the three views refer to the pass's committed generation.
layout(location=0) in vec3 cmi_Position;
layout(location=1) in vec2 cmi_Uv;
layout(location=2) in vec3 cmi_ShadeNormal;
layout(location=3) in vec4 cmi_Color;
layout(location=4) in uvec2 cmi_Instance;
layout(location=5) in vec3 cmi_FaceNormal;
layout(location=6) in vec4 cmi_MeshTangent;
layout(location=7) in vec2 cmi_MidUv;
uniform samplerBuffer cmi_PackagePool,cmi_PackageAttachment;
uniform usamplerBuffer cmi_PackageLight;
uniform vec3 cmi_CameraPos,cmi_Light0,cmi_Light1;
uniform mat4 cmi_ModelView;
uniform float cmi_PartialTick;
uniform int cmi_SampledLighting,cmi_LightingMode,cmi_ConstantAmbient;
uniform int cmi_BlockId;
vec4 cmi_VertexLevel,cmi_Tint,cmi_TexCoord0v,cmi_LightCoordv;
vec3 cmi_NormalLevel;
vec4 cmi_Tangent,cmi_MidTexCoord,cmi_EntityData,cmi_MidBlock;
#pragma cmi_include packages/vertex_pose.glsl
void main() {
    int p=int(cmi_Instance.x)*4,i=int(cmi_Instance.y>>1u);
    PackageVertexPose pose;
    if(((floatBitsToUint(texelFetch(cmi_PackagePool,p+2).w)>>24u)&8u)!=0u) {
        int a=2*(textureSize(cmi_PackageAttachment)/11)+9*i;
        pose=packageFramedVertexPose(cmi_Position,cmi_ShadeNormal,cmi_Instance,cmi_PartialTick,
            texelFetch(cmi_PackagePool,p),texelFetch(cmi_PackagePool,p+1),texelFetch(cmi_PackagePool,p+2),texelFetch(cmi_PackagePool,p+3),
            texelFetch(cmi_PackageAttachment,2*i),texelFetch(cmi_PackageAttachment,2*i+1),
            texelFetch(cmi_PackageAttachment,a),texelFetch(cmi_PackageAttachment,a+1),texelFetch(cmi_PackageAttachment,a+2),
            texelFetch(cmi_PackageAttachment,a+6),texelFetch(cmi_PackageAttachment,a+7),texelFetch(cmi_PackageAttachment,a+8));
    }else pose=packageVertexPose(cmi_Position,cmi_ShadeNormal,cmi_Instance,cmi_PartialTick,
        texelFetch(cmi_PackagePool,p),texelFetch(cmi_PackagePool,p+1),texelFetch(cmi_PackagePool,p+2),
        texelFetch(cmi_PackagePool,p+3),texelFetch(cmi_PackageAttachment,2*i),texelFetch(cmi_PackageAttachment,2*i+1));
    cmi_VertexLevel=vec4(pose.position-cmi_CameraPos,1);
    cmi_NormalLevel=pose.rotation*cmi_FaceNormal;
    cmi_Tangent=vec4(pose.tangentRotation*cmi_MeshTangent.xyz,cmi_MeshTangent.w);
    if((pose.flags&8u)!=0u){
        cmi_NormalLevel=dot(cmi_NormalLevel,cmi_NormalLevel)>1e-12?normalize(cmi_NormalLevel):vec3(0);
        cmi_Tangent.xyz=dot(cmi_Tangent.xyz,cmi_Tangent.xyz)>1e-12?normalize(cmi_Tangent.xyz):vec3(0);
    }
    cmi_MidTexCoord=vec4(cmi_MidUv,0,1);
    // Iris's immediate SBB stream has default localPos=0; the stored vertex is
    // camera-relative. Match integer truncation and signed-byte wrapping, including emission=-1.
    ivec3 mid=ivec3((vec3(.5)-cmi_VertexLevel.xyz)*64.0);
    cmi_MidBlock=vec4((mid<<24)>>24,-1);
    cmi_EntityData=vec4(float(cmi_BlockId),-1,0,0);
    uint light=cmi_SampledLighting!=0?texelFetch(cmi_PackageLight,i).x:pose.packedLight;
    cmi_LightCoordv=vec4(float((light>>4u)&15u)*16.0,float((light>>20u)&15u)*16.0,0,1);
    cmi_TexCoord0v=vec4(cmi_Uv,0,1);
    vec3 normal=pose.normal,n2=normal*normal;bool unshaded=dot(n2,n2)<1e-8;
    float shade;
    if(cmi_LightingMode==1 && (pose.flags&1u)!=0u) {
        float yFactor=cmi_ConstantAmbient!=0?.9:(3.0+normal.y)*.25;
        shade=unshaded?(cmi_ConstantAmbient!=0?.9:1.0):min(n2.x*.6+n2.z*.8+n2.y*yFactor,1.0);
    } else {
        vec3 n=unshaded?vec3(0,1,0):mat3(cmi_ModelView)*normal;
        shade=min(1.0,(max(dot(cmi_Light0,n),0.0)+max(dot(cmi_Light1,n),0.0))*.6+.4);
    }
    // Iris owns lightmap/PBR/fog. Do not multiply the lightmap into the vertex tint.
    cmi_Tint=cmi_Color*vec4(vec3(shade),1);
}
