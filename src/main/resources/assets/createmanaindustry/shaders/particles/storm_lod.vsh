uniform mat4 ModelViewMat, ProjMat;
layout(std430,binding=0) readonly buffer LodRecords { vec4 records[]; };
layout(std430,binding=1) readonly buffer LodOrder { uvec2 order[]; };
layout(std430,binding=BIND_MODELGEO) readonly buffer ModelGeo { float v[]; } geo;
#pragma cmi_include chunks/storm_lod_vertex.glsl
out vec2 vUv;
out vec3 vColor;
out float vDist;
out float vAlpha;
flat out float vHex;
flat out float vSeg;
flat out vec3 vNormalView;
flat out float vOverlay;
void main(){
    uint id=order[(cmi_LodPass==1?0u:131072u)+uint(gl_InstanceID)].x;
    uint vb=uint(gl_VertexID)*MODEL_VERTEX_FLOATS;
    vec3 local=vec3(0);vec2 uv=vec2(0);int part=0,axis=4;
    if(cmi_LodPass==1){local=vec3(geo.v[vb],geo.v[vb+1u],geo.v[vb+2u]);uv=vec2(geo.v[vb+3u],geo.v[vb+4u]);part=int(geo.v[vb+5u]);axis=int(geo.v[vb+6u]);}
    vec3 vertex,normal;
    cmiLodVertex(records[id*2u],records[id*2u+1u],local,uv,part,axis,vertex,normal,vUv);
    gl_Position=ProjMat*ModelViewMat*vec4(vertex,1);
    vNormalView=mat3(ModelViewMat)*normal;vColor=vec3(1);vDist=length(records[id*2u].xyz);vSeg=0.;vOverlay=0.;vAlpha=1.;vHex=0.;
}
