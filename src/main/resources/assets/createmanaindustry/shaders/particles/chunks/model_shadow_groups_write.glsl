layout(std430,binding=30)buffer ShadowGroupsWrite {
    uvec4 counts; uvec2 generation; uvec2 reserved; vec4 cylinder; vec4 heights; uvec2 rows[];
} shadowGroupsWrite;
uniform bool uShadowGroupsWrite;
void cmiShadowGroupRecord(uint slot,uint animation,vec3 position,float bound,bool storm){
    if(!uShadowGroupsWrite)return;
    vec3 rel=position-shadowGroupsWrite.cylinder.xyz;
    float r=shadowGroupsWrite.cylinder.w-bound;
    bool body=storm && r>0. && dot(rel.xz,rel.xz)<r*r
        && rel.y-bound>shadowGroupsWrite.heights.x && rel.y+bound<shadowGroupsWrite.heights.y;
    uint row=body?atomicAdd(shadowGroupsWrite.counts.x,1u):atomicAdd(shadowGroupsWrite.counts.y,1u);
    if(row>=shadowGroupsWrite.counts.z){atomicExchange(shadowGroupsWrite.counts.w,0u);return;}
    shadowGroupsWrite.rows[body?row:shadowGroupsWrite.counts.z-1u-row]=uvec2(slot,animation);
}
