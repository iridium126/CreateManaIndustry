uniform mat4 ModelViewMat,ProjMat;uniform vec3 uCamPos,uCamRight,uCamUp;
layout(std430,binding=1) readonly buffer Pool {vec4 data[];} pool;
layout(std430,binding=20) readonly buffer Feather {int words[];} feather;
#pragma cmi_include chunks/feather_record.glsl
out vec2 vUv;out vec3 vColor;out float vDist,vAlpha;flat out float vHex;
void main(){
    uint b=uint(feather.words[CMI_FEATHER_VISIBLE+uint(gl_InstanceID)])*4u;vec3 position,normal;
    cmiFeatherVertex(pool.data[b],pool.data[b+1u],pool.data[b+2u],pool.data[b+3u],uCamRight,uCamUp,gl_VertexID,position,normal,vUv);
    gl_Position=ProjMat*ModelViewMat*vec4(position-uCamPos,1);vColor=vec3(1);vAlpha=1.;vHex=0.;vDist=length(position-uCamPos);
}
