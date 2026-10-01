#pragma cmi_include packages/observer_state.glsl
// Two dvec4s, 64 bytes. The absolute codec base cannot be replaced by a local float or
// by quantized XYZ: a zero native delta must preserve the original non-grid double.
struct NativeObserverState { dvec4 base; dvec4 velocity; };
layout(std430,binding=7) buffer NativeObserverStates { NativeObserverState nativeState[]; };
uniform dvec3 uNativeOrigin;
struct NativePatch {
    uvec4 identity; uvec4 fence; uvec4 selection;
    uvec4 a; uvec4 b; uvec4 c; vec4 attributes; uvec4 sequence;
};
layout(std430,binding=1) readonly buffer NativeBaselines { NativePatch nativeUpdates[]; };
struct NativeCompact { uvec4 epochSequence; uvec4 selection; uvec4 a; uvec4 b; };
layout(std430,binding=6) readonly buffer NativeCommands { NativeCompact nativeCommands[]; };
const uint NATIVE_MOVE=1u,NATIVE_TELEPORT=2u,NATIVE_MOTION=4u;
NativePatch nativeReadPatch(uint i) {
    if(uCompact==0u)return nativeUpdates[i];
    NativeCompact c=nativeCommands[i];NativePatch p;
    p.identity=uvec4(0);p.fence=uvec4(c.epochSequence.xy,1u,0u);p.selection=c.selection;
    p.a=c.a;p.b=c.b;p.c=uvec4(0);p.attributes=vec4(0,0,0,uintBitsToFloat(c.b.z));
    p.sequence=uvec4(c.epochSequence.zw,0u,0u);return p;
}
dvec3 nativeAbsolute(NativePatch p) { return dvec3(packDouble2x32(p.a.xy),packDouble2x32(p.a.zw),packDouble2x32(p.b.xy)); }
dvec3 nativeBaselineVelocity(NativePatch p) { return dvec3(packDouble2x32(p.b.zw),packDouble2x32(p.c.xy),packDouble2x32(p.c.zw)); }
// Exact VecDeltaCodec Math.round ties toward +infinity over Minecraft's bounded world.
double nativeAxis(double base,int delta) {
    if(delta==0)return base;
    double scaled=base*4096.0,lower=floor(scaled);
    // Adding .5 first rounds nextDown(.5) up to 1.0. Compare the fractional part
    // instead, including negative ties, to match Java Math.round without that error.
    double encoded=lower+(scaled-lower>=0.5?1.0:0.0);
    return (encoded+double(delta))/4096.0;
}
dvec3 nativeMoved(dvec3 base,ivec3 delta) { return dvec3(nativeAxis(base.x,delta.x),nativeAxis(base.y,delta.y),nativeAxis(base.z,delta.z)); }
bool nativeFinite(dvec3 v) { return !any(isnan(v)) && !any(isinf(v)); }
bool nativePosition(dvec3 v) { return nativeFinite(v) && all(lessThanEqual(abs(v),dvec3(30000000.0))); }
float nativeYaw(uint flags) { return float(bitfieldExtract(int(flags),8,8))*(360.0/256.0); }
