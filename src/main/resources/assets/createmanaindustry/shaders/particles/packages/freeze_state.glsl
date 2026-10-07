// Optional sidecar diagnostics. Body/lifecycle ABI and physics remain unchanged.
#define CMI_PACKAGE_FREEZE_TYPES
const uint FREEZE_WORLD_NOT_READY=1u,FREEZE_SECTION_MISSING=2u,FREEZE_WORLD_UNSUPPORTED=3u,
    FREEZE_WORLD_METADATA=4u,FREEZE_WORLD_BOUNDS=5u,FREEZE_WORLD_OPPOSING=6u,
    FREEZE_MOVING_POSE=7u,FREEZE_MOVING_GEOMETRY=8u,FREEZE_MOVING_BUDGET=9u,
    FREEZE_MOVING_BVH=10u,FREEZE_MOVING_SWEEP=11u,FREEZE_MOVING_UNSUPPORTED=12u,
    FREEZE_SUPPORT_ANCESTOR=13u,FREEZE_SUPPORT_HEIGHT=14u,FREEZE_SUPPORT_CEILING=15u,
    FREEZE_ENV_BACKPRESSURE=16u,FREEZE_ENV_WATER=17u,FREEZE_ENV_HEALTH=18u,FREEZE_SECTION_VERSION=19u;
const uint FREEZE_PREDICT=1u,FREEZE_SOLVE=2u,FREEZE_CARRY=3u,FREEZE_MOVING=4u,
    FREEZE_SUPPORT=5u,FREEZE_ENVIRONMENT=6u,FREEZE_RESUME=7u;
struct PackageFreezeState { uvec4 meta; ivec4 detail; vec4 position; uvec4 velocity; };
#if defined(CMI_FREEZE_DIAGNOSTICS) && !defined(CMI_NO_FREEZE_DIAGNOSTICS)
layout(std430,binding=15) buffer FreezeStates { PackageFreezeState freezeStates[]; };
uniform bool uFreezeDiagnostics;
uniform uvec2 uFreezeStep;
#endif
void recordPackageFreeze(Body b,vec3 position,uint reason,uint stage,ivec4 detail) {
#if defined(CMI_FREEZE_DIAGNOSTICS) && !defined(CMI_NO_FREEZE_DIAGNOSTICS)
    if(!uFreezeDiagnostics)return;
    uint i=gl_GlobalInvocationID.x;
    freezeStates[i].meta.x=reason|(stage<<16u)|0x80000000u;
    freezeStates[i].meta.zw=uFreezeStep;
    freezeStates[i].detail=detail;
    freezeStates[i].position=vec4(position,b.extentYaw.y);
    freezeStates[i].velocity=floatBitsToUint(b.velocityGround);
#endif
}
void clearPackageFreeze() {
    // Keep the last failure until sampled, including a freeze that recovered
    // between diagnostic captures. Do not reset the repeat-suppression latch here.
#if defined(CMI_FREEZE_DIAGNOSTICS) && !defined(CMI_NO_FREEZE_DIAGNOSTICS)
    if(uFreezeDiagnostics)freezeStates[gl_GlobalInvocationID.x].meta.x&=0x7fffffffu;
#endif
}
void recordWorldFreezeVersion(uvec2 revision,uint wait) {
#if defined(CMI_FREEZE_DIAGNOSTICS) && !defined(CMI_NO_FREEZE_DIAGNOSTICS)
    if(uFreezeDiagnostics){
        uint i=gl_GlobalInvocationID.x;
        freezeStates[i].velocity.w=revision.x;
        freezeStates[i].meta.x|=(wait&127u)<<24u;
    }
#endif
}
