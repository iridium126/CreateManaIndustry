// Word layout is shared with StormRingClient. The last 64 words are per-frame rank masks.
#ifdef CMI_RING_REPORT
layout(r32ui,binding=7) uniform uimageBuffer cmiStormRing;
uniform bool uStormRingReport;
#else
layout(r32ui,binding=7) readonly uniform uimageBuffer cmiStormRing;
#endif
uniform bool uStormRingEnabled;
uniform float uStormRingSeconds;

vec4 cmiRingRow(uint at) {
    return uintBitsToFloat(uvec4(imageLoad(cmiStormRing,int(at)).x,imageLoad(cmiStormRing,int(at+1u)).x,
        imageLoad(cmiStormRing,int(at+2u)).x,imageLoad(cmiStormRing,int(at+3u)).x));
}

float cmiRingElapsed(float now, float start) {
    float age = now - start;
    return age < -52428.8 ? age + 104857.6 : age > 52428.8 ? age - 104857.6 : age;
}

uint cmiRingSample(uint member, out vec3 target, out vec3 reference, out vec3 axis, out uint phase) {
    if (!uStormRingEnabled || member >= 131072u) return 0u;
    uint assignment = imageLoad(cmiStormRing,int(member)).x, slot = assignment & 255u;
    if (slot == 0u || slot > 64u) return 0u;
    uint at = 131072u + (slot-1u)*16u;
    vec4 center = cmiRingRow(at), a = cmiRingRow(at+4u), right = cmiRingRow(at+8u), travel = cmiRingRow(at+12u);
    float age = cmiRingElapsed(uStormRingSeconds, a.w);
    phase = uint(right.w);
    if (age < 0. || phase == 0u) return 0u;
    float angle = float(assignment >> 8u) * .3926990817 - age * .5235987756;
    axis = a.xyz;
    vec3 up = cross(axis, right.xyz);
    vec3 radial = 1.75*(cos(angle)*right.xyz+sin(angle)*up);
    target = center.xyz + clamp(cmiRingElapsed(uStormRingSeconds, center.w), 0., .1)*travel.xyz + radial;
    reference = travel.xyz - .5235987756*cross(axis, radial);
    return assignment;
}

#ifdef CMI_RING_REPORT
void cmiRingReport(uint assignment, uint phase, vec3 pos, vec3 vel, vec3 target, vec3 reference) {
    if (!uStormRingReport || assignment == 0u || phase != 3u) return;
    if (distance(pos,target) <= .4 && distance(vel,reference) <= 1.)
        imageAtomicOr(cmiStormRing,int(132096u+(assignment&255u)-1u),1u<<(assignment>>8u));
}
#endif
