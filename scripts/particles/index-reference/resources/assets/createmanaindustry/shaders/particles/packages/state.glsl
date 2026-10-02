// Package pass-local bindings; deliberately reuse slots rather than extending the global ABI.
#pragma cmi_include packages/body_lifecycle.glsl
struct Body {
    vec4 positionMass; // centre.xyz, inverse mass (zero = static collider)
    vec4 velocityGround; // blocks/second.xyz, support flag
    vec4 extentYaw; // half extents.xyz, yaw in degrees
    vec4 previousSleep; // previous centre.xyz; -1 invalid, -2 prepared, -3 retired, -4 collision-frozen
};
void freezeForMissingCollision(inout Body body,vec3 stablePosition) {
    body.positionMass.xyz=stablePosition;
    body.previousSleep.xyz=stablePosition;
    body.previousSleep.w=PACKAGE_COLLISION_FROZEN;
}
#ifdef CMI_BODY_INPLACE
// Apply kernels only read/write their own body; all inter-body constraints are immutable.
layout(std430,binding=0) buffer InputBodies { Body src[]; };
#else
layout(std430,binding=0) readonly buffer InputBodies { Body src[]; };
#endif
layout(std430,binding=1) writeonly buffer OutputBodies { Body dst[]; };
layout(std430,binding=2) buffer Heads { uint heads[]; };
layout(std430,binding=3) buffer Links { uint links[]; };
uniform uint uCount;
uniform uint uTableMask;
uniform float uCellSize;
#ifdef CMI_EXACT_RANGES
const bool uRangeGrid=true;
#else
const bool uRangeGrid=false;
#endif
uniform uint uCandidateBudget;
const uint END = 0xffffffffu;
const uint GRID_OVERFLOW = 0xfffffffeu;
ivec3 cellOf(vec3 p) { return ivec3(floor(p / uCellSize)); }
uint hashCell(ivec3 cell) {
    uvec3 c=uvec3(cell);
    return ((c.x*73856093u) ^ (c.y*19349663u) ^ (c.z*83492791u)) & uTableMask;
}
// Exact-cell open addressing. The representative is a stable body index; bodies
// are never sorted or moved by this index. A failed probe is unknown, not empty.
uint rangeSlot(ivec3 cell) {
    uint hash=hashCell(cell);
    for(uint probe=0u;probe<32u;probe++) {
        uint slot=(hash+probe)&uTableMask,representative=heads[slot*4u];
        if(representative==0u)return END;
        if(all(equal(cellOf(src[representative-1u].positionMass.xyz),cell)))return slot;
    }
    return GRID_OVERFLOW;
}
bool gridBudget(ivec3 lo,ivec3 hi) {
#ifdef CMI_BOUNDED_LINKED
    uint total=0u;
    for(int z=lo.z;z<=hi.z;z++)for(int y=lo.y;y<=hi.y;y++)for(int x=lo.x;x<=hi.x;x++) {
        // Include hash collisions: this bounds actual linked-list visits too.
        uint n=heads[uTableMask+1u+hashCell(ivec3(x,y,z))];
        if(n>uCandidateBudget-total)return false;
        total+=n;
    }
    return true;
#else
    if(!uRangeGrid)return true;
    uint total=0u;
    for(int z=lo.z;z<=hi.z;z++)for(int y=lo.y;y<=hi.y;y++)for(int x=lo.x;x<=hi.x;x++) {
        uint slot=rangeSlot(ivec3(x,y,z));
        if(slot==GRID_OVERFLOW)return false;
        if(slot!=END) {
            uint n=heads[slot*4u+1u];
            if(n>uCandidateBudget-total)return false;
            total+=n;
        }
    }
    return true;
#endif
}
void cellCursor(ivec3 cell,out uint cursor,out uint end) {
    if(uRangeGrid) {
        uint slot=rangeSlot(cell);
        cursor=slot>=GRID_OVERFLOW?0u:heads[slot*4u+2u];
        end=slot>=GRID_OVERFLOW?0u:cursor+heads[slot*4u+1u];
    } else { cursor=heads[hashCell(cell)];end=END; }
}
uint cursorBody(uint cursor){return uRangeGrid?links[cursor]:cursor;}
uint cursorNext(uint cursor,uint body){return uRangeGrid?cursor+1u:links[body];}
