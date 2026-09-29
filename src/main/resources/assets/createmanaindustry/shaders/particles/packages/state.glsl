// Package pass-local bindings; deliberately reuse slots rather than extending the global ABI.
struct Body {
    vec4 positionMass; // centre.xyz, inverse mass (zero = static collider)
    vec4 velocityGround; // blocks/second.xyz, support flag
    vec4 extentYaw; // half extents.xyz, yaw in degrees
    vec4 previousSleep; // previous centre.xyz, reserved sleep counter
};
layout(std430,binding=0) readonly buffer InputBodies { Body src[]; };
layout(std430,binding=1) writeonly buffer OutputBodies { Body dst[]; };
layout(std430,binding=2) buffer Heads { uint heads[]; };
layout(std430,binding=3) buffer Links { uint links[]; };
uniform uint uCount;
uniform uint uTableMask;
uniform float uCellSize;
const uint END = 0xffffffffu;
ivec3 cellOf(vec3 p) { return ivec3(floor(p / uCellSize)); }
uint hashCell(ivec3 cell) {
    uvec3 c=uvec3(cell);
    return ((c.x*73856093u) ^ (c.y*19349663u) ^ (c.z*83492791u)) & uTableMask;
}
