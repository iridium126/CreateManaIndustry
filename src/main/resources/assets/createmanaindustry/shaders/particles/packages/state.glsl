// Package pass-local bindings; deliberately reuse slots rather than extending the global ABI.
#pragma cmi_include packages/body_lifecycle.glsl
struct Body {
    vec4 positionMass; // centre.xyz, inverse mass (zero = static collider)
    vec4 velocityGround; // blocks/second.xyz, support flag
    vec4 extentYaw; // half extents.xyz, yaw in degrees
    vec4 previousSleep; // previous centre.xyz; -1 handback, -2 prepared, -3 retired, -4 collision-frozen
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
uniform uint uCandidateBudget;
const uint END = 0xffffffffu;
const uint GRID_OVERFLOW = 0xfffffffeu;
ivec3 cellOf(vec3 p) { return ivec3(floor(p / uCellSize)); }
uint hashCell(ivec3 cell) {
    uvec3 c=uvec3(cell);
    return ((c.x*73856093u) ^ (c.y*19349663u) ^ (c.z*83492791u)) & uTableMask;
}
bool gridBudget(ivec3 lo,ivec3 hi){return true;}
void cellCursor(ivec3 cell,out uint cursor,out uint end){cursor=heads[hashCell(cell)];end=END;}
uint cursorBody(uint cursor){return cursor;}
uint cursorNext(uint cursor,uint body){return links[body];}
