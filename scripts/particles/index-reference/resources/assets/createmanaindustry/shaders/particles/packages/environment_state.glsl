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
