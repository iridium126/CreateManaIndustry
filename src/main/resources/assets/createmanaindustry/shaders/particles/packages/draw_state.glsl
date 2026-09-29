layout(std430,binding=0) readonly buffer Pool { vec4 pool[]; };
layout(std430,binding=1) readonly buffer Admission { uvec4 admitted[]; };
layout(std430,binding=2) buffer Commands { uvec4 commands[]; };
layout(std430,binding=3) buffer Instances { uvec2 instances[]; };
layout(std430,binding=4) buffer Cursors { uint cursors[]; };
layout(std430,binding=5) readonly buffer Meshes { uvec4 meshes[]; };
uniform uint uCount,uMeshCount;
uniform vec3 uCamPos;
uniform vec4 uFrustum[6];
bool visible(uint slot) {
    // Includes hook, sway, rigging and both interpolation endpoints.
    vec3 a=pool[4u*slot].xyz-uCamPos, b=pool[4u*slot+1u].xyz-uCamPos;
    uint box=floatBitsToUint(pool[4u*slot].w), rig=floatBitsToUint(pool[4u*slot+1u].w);
    float r=box<uMeshCount?uintBitsToFloat(meshes[box].z):0.0;
    if(rig<uMeshCount)r=max(r,uintBitsToFloat(meshes[rig].z));
    r+=abs(pool[4u*slot+3u].z)+2.0;
    for(int j=0;j<6;j++)if(max(dot(uFrustum[j].xyz,a),dot(uFrustum[j].xyz,b))+uFrustum[j].w < -r)return false;
    return true;
}
uint mesh(uint slot,bool rig) { return floatBitsToUint(pool[4u*slot+(rig?1u:0u)].w); }
