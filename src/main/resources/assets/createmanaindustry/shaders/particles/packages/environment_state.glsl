#ifndef CMI_ENVIRONMENT_STATE
#define CMI_ENVIRONMENT_STATE
struct PackageEnvironment { uvec4 identity; uvec4 lease; uvec4 control; vec4 state; };
struct PackageEnvironmentSample { ivec4 contact; uvec4 timeline; vec4 position; };
const uint PACKAGE_ENV_HISTORY=20u;
const uint PACKAGE_ENV_PAGE_SAMPLES=19u;
layout(std430,binding=13) buffer EnvironmentStates { PackageEnvironment environment[]; };
layout(std430,binding=14) buffer EnvironmentSamples { PackageEnvironmentSample environmentSamples[]; };
uniform bool uEnvironmentReady;
uint environmentWorkspace(uint i){return i;}
uint environmentInline(uint i){return uint(environment.length())+i;}
uint environmentAllocator(){return uint(environment.length())*2u;}
uint environmentPendingBase(){return environmentAllocator()+1u;}
uint environmentStackBase(){return environmentPendingBase()+(uint(environment.length())+127u)/128u;}
uint environmentPagesBase(){return environmentStackBase()+(uint(environment.length())+11u)/12u;}
uint environmentFreePage(uint at){
    uint word=at%12u;PackageEnvironmentSample entry=environmentSamples[environmentStackBase()+at/12u];
    return word<4u?uint(entry.contact[word]):word<8u?entry.timeline[word-4u]:floatBitsToUint(entry.position[word-8u]);
}
void writeEnvironmentFreePage(uint at,uint page){
    uint record=environmentStackBase()+at/12u,word=at%12u;
    if(word<4u)environmentSamples[record].contact[word]=int(page);
    else if(word<8u)environmentSamples[record].timeline[word-4u]=page;
    else environmentSamples[record].position[word-8u]=uintBitsToFloat(page);
}
uint environmentRecord(uint i,uint serial){
    uint page=environmentSamples[environmentWorkspace(i)].timeline.y,slot=serial%PACKAGE_ENV_HISTORY;
    return page==0u||slot==0u?environmentInline(i):environmentPagesBase()+(page-1u)*PACKAGE_ENV_PAGE_SAMPLES+slot-1u;
}
// Allocation and release occur in different dispatches. A barrier separates all
// pushes from subsequent pops; stack publication never races an allocating reader.
void releaseEnvironmentPage(uint i){
    uint page=environmentSamples[environmentWorkspace(i)].timeline.y;if(page==0u)return;
    environmentSamples[environmentWorkspace(i)].timeline.y=0u;
    uint slot=uint(atomicAdd(environmentSamples[environmentAllocator()].contact.x,1));
    if(slot<uint(environmentSamples[environmentAllocator()].contact.z))writeEnvironmentFreePage(slot,page);
}
void prepareEnvironmentPage(uint i){
    if(!uEnvironmentReady)return;
    PackageEnvironment e=environment[i];
    if(e.control.z==0u||e.control.y!=0u||e.lease.z==e.lease.w||environmentSamples[environmentWorkspace(i)].timeline.y!=0u)return;
    int available=atomicAdd(environmentSamples[environmentAllocator()].contact.x,0);
    uint capacity=uint(environmentSamples[environmentAllocator()].contact.z);
    while(available>0&&uint(available)<=capacity){
        int previous=atomicCompSwap(environmentSamples[environmentAllocator()].contact.x,available,available-1);
        if(previous==available){
            uint page=environmentFreePage(uint(available-1));if(page==0u||page>capacity)return;
            uint slot=e.lease.w%PACKAGE_ENV_HISTORY;
            if(slot!=0u)environmentSamples[environmentPagesBase()+(page-1u)*PACKAGE_ENV_PAGE_SAMPLES+slot-1u]=environmentSamples[environmentInline(i)];
            environmentSamples[environmentWorkspace(i)].timeline.y=page;
            return;
        }
        available=previous;
    }
}
void markEnvironmentPending(uint i){
    uint word=i>>5u;
    atomicOr(environmentSamples[environmentPendingBase()+(word>>2u)].contact[word&3u],int(1u<<(i&31u)));
}
void clearEnvironmentPending(uint i){
    uint word=i>>5u;
    atomicAnd(environmentSamples[environmentPendingBase()+(word>>2u)].contact[word&3u],int(~(1u<<(i&31u))));
}
bool environmentBlocked(uint i){
    return uEnvironmentReady&&environment[i].control.z!=0u
        &&(environment[i].control.y!=0u||environment[i].lease.z-environment[i].lease.w>=PACKAGE_ENV_HISTORY
            ||(environment[i].lease.z!=environment[i].lease.w&&environmentSamples[environmentWorkspace(i)].timeline.y==0u));
}
#ifdef CMI_PACKAGE_FREEZE_TYPES
uint environmentFreezeReason(uint i){
    PackageEnvironment e=environment[i];
    return e.control.y==2u?FREEZE_ENV_CONFIRMATION:e.control.y==0u?FREEZE_ENV_BACKPRESSURE:e.state.x<=.5?FREEZE_ENV_HEALTH:FREEZE_ENV_WATER;
}
ivec4 environmentFreezeDetail(uint i){PackageEnvironment e=environment[i];return ivec4(e.lease.z-e.lease.w,e.lease.z,e.lease.w,floatBitsToUint(e.state.x));}
#endif
#endif
