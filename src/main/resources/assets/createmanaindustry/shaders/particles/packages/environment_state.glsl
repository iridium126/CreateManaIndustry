struct PackageEnvironment { uvec4 identity; uvec4 lease; uvec4 control; vec4 state; };
struct PackageEnvironmentSample { ivec4 contact; uvec4 timeline; vec4 position; };
const uint PACKAGE_ENV_HISTORY=20u;
layout(std430,binding=13) buffer EnvironmentStates { PackageEnvironment environment[]; };
layout(std430,binding=14) buffer EnvironmentSamples { PackageEnvironmentSample environmentSamples[]; };
uniform bool uEnvironmentReady;
bool environmentBlocked(uint i){
    return uEnvironmentReady&&environment[i].control.z!=0u
        &&(environment[i].control.y!=0u||environment[i].lease.z-environment[i].lease.w>=PACKAGE_ENV_HISTORY);
}
#ifdef CMI_PACKAGE_FREEZE_TYPES
uint environmentFreezeReason(uint i){
    PackageEnvironment e=environment[i];
    return e.control.y==0u?FREEZE_ENV_BACKPRESSURE:e.state.x<=.5?FREEZE_ENV_HEALTH:FREEZE_ENV_WATER;
}
ivec4 environmentFreezeDetail(uint i){PackageEnvironment e=environment[i];return ivec4(e.lease.z-e.lease.w,e.lease.z,e.lease.w,floatBitsToUint(e.state.x));}
#endif
