struct ObserverState {
    uvec4 identity;
    uvec4 fence; // authority epoch.xy; observer stream.zw (full 64-bit pairs)
    uvec4 lifecycle; // last sequence.xy; 0 empty / 1 live / 2 retired; server index
    ivec4 positionFlags;
    ivec4 velocityYaw;
    vec4 originTime; // region origin relative to shared physics origin; confirmed state receipt
    vec4 extentTime; // half extents; prediction anchor receipt
    vec4 anchor; // feet.xyz; yaw at correction start
    vec4 correction; // previous displayed feet.xyz; correction receipt
};
struct ObserverPatch {
    uvec4 identity;
    uvec4 fence;
    uvec4 selection; // local slot, server index, mask (32 baseline / 16 release / 1..15 fields), reserved
    ivec4 positionFlags;
    ivec4 velocityYaw;
    vec4 originTime;
    vec4 extent;
    uvec4 sequence; // full 64-bit sequence.xy; reserved.zw
};
layout(std430,binding=0) buffer ObserverStates { ObserverState state[]; };
layout(std430,binding=1) readonly buffer ObserverPatches { ObserverPatch updates[]; };
layout(std430,binding=2) buffer ObserverControl { uint invalid; uint staleCount; uint activeCount; uint padding; };
struct ObserverCompact {
    uvec4 fence;
    uvec4 selection; // local slot, server index, field mask, receipt float bits
    ivec4 positionFlags;
    uvec4 velocitySequence; // packed signed shorts vx/vy, vz/yaw; sequence.xy
};
layout(std430,binding=6) readonly buffer ObserverCompactUpdates { ObserverCompact compactUpdates[]; };
uniform uint uSlots,uPatches,uCompact;
uniform float uNow,uSmoothing,uPrediction;
const uint OBSERVER_BASELINE=32u,OBSERVER_RELEASE=16u;
ObserverPatch observerReadPatch(uint i) {
    if(uCompact==0u)return updates[i];
    ObserverCompact c=compactUpdates[i];ObserverPatch p;
    // The immutable (authority, stream, server index) namespace names exactly one admitted
    // identity. Validation checks all three before mutation; no CPU pool index is a network ID.
    p.identity=c.selection.x<uSlots?state[c.selection.x].identity:uvec4(0);
    p.fence=c.fence;p.selection=uvec4(c.selection.xyz,0u);
    p.positionFlags=c.positionFlags;
    p.velocityYaw=ivec4(bitfieldExtract(int(c.velocitySequence.x),0,16),bitfieldExtract(int(c.velocitySequence.x),16,16),
                       bitfieldExtract(int(c.velocitySequence.y),0,16),bitfieldExtract(int(c.velocitySequence.y),16,16));
    p.originTime=vec4(0,0,0,uintBitsToFloat(c.selection.w));p.extent=vec4(0);
    p.sequence=uvec4(c.velocitySequence.zw,0u,0u);
    // A compact record cannot introduce an identity or modify the stored baseline geometry.
    if(c.selection.z==OBSERVER_BASELINE)p.selection.z=0u;
    return p;
}
bool observerLongPositive(uvec2 value) { return any(notEqual(value,uvec2(0))) && (value.y&0x80000000u)==0u; }
bool observerLongGreater(uvec2 a,uvec2 b) { return a.y>b.y || (a.y==b.y && a.x>b.x); }
bool observerFinite(vec4 v) { return !any(isnan(v)) && !any(isinf(v)); }
float observerYaw(ObserverState s) {
    return float(uint(s.velocityYaw.w)&65535u)*(360.0/65536.0);
}
float observerAngle(float a,float b) { return mod(b-a+180.0,360.0)-180.0; }
vec3 observerVelocity(ObserverState s) {
    return vec3(s.velocityYaw.xyz)*(1.0/1024.0);
}
float observerBlend(ObserverState s,float now) { return clamp((now-s.correction.w)/uSmoothing,0.0,1.0); }
vec3 observerPredicted(ObserverState s,float now) {
    float until=min(now,s.originTime.w+uPrediction);
    return s.anchor.xyz+observerVelocity(s)*max(0.0,until-s.extentTime.w);
}
vec3 observerDisplayed(ObserverState s,float now) {
    vec3 from=s.correction.xyz+observerVelocity(s)*clamp(now-s.correction.w,0.0,uPrediction);
    return mix(from,observerPredicted(s,now),observerBlend(s,now));
}
float observerDisplayedYaw(ObserverState s,float now) {
    return s.anchor.w+observerAngle(s.anchor.w,observerYaw(s))*observerBlend(s,now);
}
