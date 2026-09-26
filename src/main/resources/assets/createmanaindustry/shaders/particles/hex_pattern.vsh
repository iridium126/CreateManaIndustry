layout(std430, binding = BIND_POOL_WRITE) readonly buffer HexPool { vec4 data[]; } pool;
layout(std430, binding = 28) readonly buffer HexPoints { vec4 point[]; } points;
#pragma cmi_include chunks/hex_pattern.glsl
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec3 uCamPos;
out vec4 vertexColor;
vec2 unit(vec2 v) { float len = length(v); return len > 0.000001 ? v / len : vec2(0); }
vec2 rotate2(vec2 v, float a) { return vec2(v.x*cos(a)-v.y*sin(a), v.y*cos(a)+v.x*sin(a)); }
float turn(uint offset, uint i, uint n) {
    if (i == 0u || i+1u >= n) return 0.0;
    return points.point[offset+i].z;
}
float joinOffset(uint offset, uint i, uint n, float radius) {
    if (i == 0u || i+1u >= n) return 0.0;
    float halfTan = points.point[offset+i].w;
    float limit = min(length(points.point[offset+i].xy-points.point[offset+i-1u].xy),
                      length(points.point[offset+i+1u].xy-points.point[offset+i].xy)) / radius;
    return clamp(halfTan, -limit, limit);
}
void main() {
    uint slot = uint(gl_VertexID) / HEX_VERTEX_STRIDE;
    uint b = hexBase(slot), n = floatBitsToUint(hexInput.v[b].z);
    uint offset = floatBitsToUint(hexInput.v[b+4u].x);
    uint layerSize = (n-1u)*42u+60u;
    uint v = uint(gl_VertexID) % HEX_VERTEX_STRIDE;
    bool inner = v >= layerSize;
    v %= layerSize;
    float radius = inner ? 0.07 : 0.175;
    vec2 p;
    if (v < (n-1u)*42u) {
        uint i = v/42u, part = v%42u;
        vec2 a = points.point[offset+i].xy, z = points.point[offset+i+1u].xy;
        vec2 tangent = unit(z-a)*radius, normal = vec2(-tangent.y,tangent.x);
        if (part < 12u) {
            float lo = joinOffset(offset,i,n,radius), hi = joinOffset(offset,i+1u,n,radius);
            vec2 corners[6] = vec2[6](a+tangent*max(0.0,lo)+normal, a,
                a+tangent*max(0.0,-lo)-normal, z-tangent*max(0.0,-hi)-normal,
                z, z-tangent*max(0.0,hi)+normal);
            const int triangles[12] = int[12](0,1,2,0,2,3,0,3,4,0,4,5);
            p = corners[triangles[part]];
        } else {
            float angle = turn(offset,i,n);
            uint steps = uint(ceil(abs(angle)*10.0/3.141592653589793));
            uint j = (part-12u)/3u, c = (part-12u)%3u;
            p = a;
            if (i > 0u && j < steps && c != 0u) {
                float f = float(j+c-1u)/float(steps);
                float rot = angle < 0.0 ? -angle*f : -angle*(1.0-f);
                p += rotate2(angle < 0.0 ? normal : -normal,rot);
            }
        }
    } else {
        uint cap = v-(n-1u)*42u;
        uint endpoint = cap < 30u ? 0u : n-1u;
        uint previous = cap < 30u ? 1u : n-2u;
        vec2 a = points.point[offset+endpoint].xy;
        vec2 tangent = unit(a-points.point[offset+previous].xy)*radius;
        vec2 normal = vec2(-tangent.y,tangent.x);
        uint j = (cap%30u)/3u, c = cap%3u;
        p = a;
        if (c != 0u) p += rotate2(normal,-3.141592653589793*(1.0-float(j+c-1u)/10.0));
    }
    uint poolIndex = hexLive.index[slot]-1u;
    vec4 color = pool.data[poolIndex*4u+2u];
    vertexColor = vec4(inner ? hexScreenColor(color.rgb) : color.rgb,color.a);
    uint t = 4096u+slot*8u;
    float c = uintBitsToFloat(hexLive.index[t]), sn = uintBitsToFloat(hexLive.index[t+1u]);
    vec3 local = vec3(p,inner ? 0.01 : 0.0)*uintBitsToFloat(hexLive.index[t+2u]);
    local.y += uintBitsToFloat(hexLive.index[t+3u]);
    local.z += uintBitsToFloat(hexLive.index[t+4u]);
    local.xz = vec2(c*local.x+sn*local.z,-sn*local.x+c*local.z);
    vec3 world = pool.data[poolIndex*4u].xyz + local;
    gl_Position = ProjMat*ModelViewMat*vec4(world-uCamPos,1);
}
