layout(std430,binding=30)readonly buffer ShadowGroupsRead {
    uvec4 counts;uvec2 generation;uvec2 reserved;vec4 cylinder;vec4 heights;uvec2 rows[];
} shadowGroupsRead;
uniform bool uShadowGroupsRead;
bool cmiShadowGroupsValid(uint count){
    return uShadowGroupsRead && shadowGroupsRead.counts.w==1u
        && shadowGroupsRead.generation==uPopulationGeneration
        && shadowGroupsRead.counts.x<=shadowGroupsRead.counts.z
        && shadowGroupsRead.counts.y<=shadowGroupsRead.counts.z
        && shadowGroupsRead.counts.x+shadowGroupsRead.counts.y==count;
}
