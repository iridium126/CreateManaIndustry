#include "Common.h"
#include "ParticleDevice.h"
#include "Interop.h"
#include "Spatial.h"
#include "StormStep.h"
#include "StormSpatial.h"
#include "Version.h"
#include <optix_function_table_definition.h>
#include <optix_stubs.h>
#include <optix_stack_size.h>
#include "TerrainAcceleration.h"
#include <process.h>
#include <vector>
#include <memory>
#include <array>
#include <chrono>
#include <algorithm>
#include <sstream>
#include <fstream>
#include <iterator>
#include <cmath>

namespace {
void rtChecked(OptixResult r,const char* call){if(r!=OPTIX_SUCCESS)throw std::runtime_error(std::string(call)+": "+optixGetErrorName(r));}
#define rt(call) rtChecked(call,#call)
struct Guard {SRWLOCK& lock;Guard(SRWLOCK& l):lock(l){AcquireSRWLockExclusive(&lock);}~Guard(){ReleaseSRWLockExclusive(&lock);}};
struct Engine {
    std::unique_ptr<TerrainAcceleration> terrainCache;
    CUcontext context{};CUstream stream{};OptixDeviceContext optix{};CUmodule grid{};CUfunction validate{},prepare{},seal{};
    OptixModule module{};OptixProgramGroup groups[3]{};OptixPipeline pipeline{};OptixShaderBindingTable sbt{};
    CUdeviceptr vertices{},indices{},unitGas{},instances{},gas{},scratch{},params{},records{},temporary{},stormHints{};size_t owned{},gasBytes{},scratchBytes{};
    OptixTraversableHandle unitHandle{},handle{};unsigned capacity{},terrainCapacity{},probes{};bool built{},referenceRefit{},borrowedStream{};unsigned builtInstances{};SpatialParams* host{};unsigned* receipts{};
    void alloc(CUdeviceptr& p,size_t bytes){bytes=std::max<size_t>(bytes,16);reserveBytes(bytes);try{checked(cuMemAlloc(&p,bytes));}catch(...){particleRtOwnedBytes-=bytes;throw;}owned+=bytes;}
    Engine(unsigned cap,unsigned terrain,unsigned rays,bool reference,CUstream shared=nullptr):
        capacity(cap),terrainCapacity(terrain),probes(rays),referenceRefit(reference),borrowedStream(shared!=nullptr){
        try{context=acquireContext();if(shared)stream=shared;else checked(cuStreamCreate(&stream,CU_STREAM_NON_BLOCKING));rt(optixInit());OptixDeviceContextOptions opts{};rt(optixDeviceContextCreate(context,&opts,&optix));
        if(!shared){
            auto fatbin=embeddedDevice(101);checked(cuModuleLoadData(&grid,fatbin.data()));checked(cuModuleGetFunction(&validate,grid,"validateSpatial"));checked(cuModuleGetFunction(&prepare,grid,"prepareSpatial"));checked(cuModuleGetFunction(&seal,grid,"sealSpatial"));
        }else alloc(stormHints,cap*sizeof(float4));
        float v[]={-1,-1,-1,1,-1,-1,1,1,-1,-1,1,-1,-1,-1,1,1,-1,1,1,1,1,-1,1,1};
        unsigned ix[]={0,2,1,0,3,2,4,5,6,4,6,7,0,4,7,0,7,3,1,2,6,1,6,5,0,1,5,0,5,4,3,7,6,3,6,2};
        alloc(vertices,sizeof(v));alloc(indices,sizeof(ix));checked(cuMemcpyHtoD(vertices,v,sizeof(v)));checked(cuMemcpyHtoD(indices,ix,sizeof(ix)));
        unsigned flags=OPTIX_GEOMETRY_FLAG_DISABLE_ANYHIT;OptixBuildInput input{};input.type=OPTIX_BUILD_INPUT_TYPE_TRIANGLES;input.triangleArray.vertexBuffers=&vertices;input.triangleArray.numVertices=8;input.triangleArray.vertexFormat=OPTIX_VERTEX_FORMAT_FLOAT3;input.triangleArray.vertexStrideInBytes=12;input.triangleArray.indexBuffer=indices;input.triangleArray.numIndexTriplets=12;input.triangleArray.indexFormat=OPTIX_INDICES_FORMAT_UNSIGNED_INT3;input.triangleArray.indexStrideInBytes=12;input.triangleArray.flags=&flags;input.triangleArray.numSbtRecords=1;
        OptixAccelBuildOptions options{};options.buildFlags=OPTIX_BUILD_FLAG_PREFER_FAST_TRACE;options.operation=OPTIX_BUILD_OPERATION_BUILD;OptixAccelBufferSizes size{};rt(optixAccelComputeMemoryUsage(optix,&options,&input,1,&size));alloc(unitGas,size.outputSizeInBytes);alloc(temporary,size.tempSizeInBytes);
        rt(optixAccelBuild(optix,stream,&options,&input,1,temporary,size.tempSizeInBytes,unitGas,size.outputSizeInBytes,&unitHandle,nullptr,0));checked(cuStreamSynchronize(stream));cuMemFree(temporary);temporary=0;owned-=std::max<size_t>(16,size.tempSizeInBytes);particleRtOwnedBytes-=std::max<size_t>(16,size.tempSizeInBytes);
        alloc(instances,(cap+2*terrain)*sizeof(OptixInstance));input={};input.type=OPTIX_BUILD_INPUT_TYPE_INSTANCES;input.instanceArray.instances=instances;input.instanceArray.numInstances=cap+2*terrain;options.buildFlags=OPTIX_BUILD_FLAG_PREFER_FAST_TRACE|(referenceRefit?OPTIX_BUILD_FLAG_ALLOW_UPDATE:0);rt(optixAccelComputeMemoryUsage(optix,&options,&input,1,&size));gasBytes=size.outputSizeInBytes;scratchBytes=std::max(size.tempSizeInBytes,size.tempUpdateSizeInBytes);alloc(gas,gasBytes);alloc(scratch,scratchBytes);alloc(params,sizeof(SpatialParams));checked(cuMemHostAlloc((void**)&host,sizeof(SpatialParams)*3,0));checked(cuMemHostAlloc((void**)&receipts,sizeof(unsigned)*3,0));
        auto ptx=embeddedDevice(102);OptixModuleCompileOptions mc{};mc.optLevel=OPTIX_COMPILE_OPTIMIZATION_DEFAULT;OptixPipelineCompileOptions pc{};pc.traversableGraphFlags=OPTIX_TRAVERSABLE_GRAPH_FLAG_ALLOW_SINGLE_LEVEL_INSTANCING;pc.numPayloadValues=5;pc.numAttributeValues=2;pc.pipelineLaunchParamsVariableName="params";pc.usesPrimitiveTypeFlags=OPTIX_PRIMITIVE_TYPE_FLAGS_TRIANGLE;char log[8192];size_t length=sizeof(log);
        auto status=optixModuleCreate(optix,&mc,&pc,ptx.data(),ptx.size(),log,&length,&module);if(status!=OPTIX_SUCCESS)throw std::runtime_error(log);
        OptixProgramGroupOptions po{};for(unsigned i=0;i<3;i++){OptixProgramGroupDesc d{};if(i==0){d.kind=OPTIX_PROGRAM_GROUP_KIND_RAYGEN;d.raygen.module=module;d.raygen.entryFunctionName="__raygen__spatial";}else if(i==1){d.kind=OPTIX_PROGRAM_GROUP_KIND_MISS;d.miss.module=module;d.miss.entryFunctionName="__miss__spatial";}else{d.kind=OPTIX_PROGRAM_GROUP_KIND_HITGROUP;d.hitgroup.moduleCH=module;d.hitgroup.entryFunctionNameCH="__closesthit__spatial";}length=sizeof(log);status=optixProgramGroupCreate(optix,&d,1,&po,log,&length,&groups[i]);if(status!=OPTIX_SUCCESS)throw std::runtime_error(log);}
        OptixPipelineLinkOptions link{};link.maxTraceDepth=1;length=sizeof(log);rt(optixPipelineCreate(optix,&pc,&link,groups,3,log,&length,&pipeline));OptixStackSizes ss{};for(auto group:groups)rt(optixUtilAccumulateStackSizes(group,&ss,pipeline));unsigned a,b,c;rt(optixUtilComputeStackSizes(&ss,1,0,0,&a,&b,&c));rt(optixPipelineSetStackSize(pipeline,a,b,c,2));
        struct alignas(OPTIX_SBT_RECORD_ALIGNMENT) Record {char h[OPTIX_SBT_RECORD_HEADER_SIZE];};Record rs[3]{};for(unsigned i=0;i<3;i++)rt(optixSbtRecordPackHeader(groups[i],&rs[i]));alloc(records,sizeof(rs));checked(cuMemcpyHtoD(records,rs,sizeof(rs)));sbt.raygenRecord=records;sbt.missRecordBase=records+sizeof(Record);sbt.missRecordStrideInBytes=sizeof(Record);sbt.missRecordCount=1;sbt.hitgroupRecordBase=records+2*sizeof(Record);sbt.hitgroupRecordStrideInBytes=sizeof(Record);sbt.hitgroupRecordCount=1;
        }catch(...){release();throw;}
    }
    void buildIas(SpatialParams& p,unsigned count){
        OptixBuildInput input{};input.type=OPTIX_BUILD_INPUT_TYPE_INSTANCES;input.instanceArray.instances=instances;input.instanceArray.numInstances=std::max(1u,count);OptixAccelBuildOptions options{};options.buildFlags=OPTIX_BUILD_FLAG_PREFER_FAST_TRACE|(referenceRefit?OPTIX_BUILD_FLAG_ALLOW_UPDATE:0);options.operation=referenceRefit&&built&&builtInstances==input.instanceArray.numInstances?OPTIX_BUILD_OPERATION_UPDATE:OPTIX_BUILD_OPERATION_BUILD;
        rt(optixAccelBuild(optix,stream,&options,&input,1,scratch,scratchBytes,gas,gasBytes,&handle,nullptr,0));
        built=true;builtInstances=input.instanceArray.numInstances;p.traversable=handle;
    }
    void trace(SpatialParams& p){
        checked(cuMemcpyHtoDAsync(params,&p,sizeof(p),stream));
        rt(optixLaunch(pipeline,stream,params,sizeof(p),&sbt,std::max(1u,p.members),1,1));
    }
    unsigned publishTerrain(unsigned slot,const TerrainSnapshot& snapshot,SpatialParams& p,unsigned dynamicInstances){
        if(!terrainCache)terrainCache=std::make_unique<TerrainAcceleration>(terrainCapacity);
        unsigned count=terrainCache->update(optix,stream,slot,snapshot,capacity,dynamicInstances,instances);
        p.terrainViews=terrainCache->deviceViews();p.terrainRows=terrainCache->deviceRows();
        return count;
    }
    void enqueue(unsigned slot,const BridgeTicket& ticket,uint64_t nonce,uint64_t epoch,uint64_t generation,uint64_t scene,unsigned kind,unsigned members,unsigned terrain,const TerrainSnapshot* snapshot,CUevent* marks){
        auto& p=host[slot];p={};p.input=(unsigned*)ticket.input;p.output=(unsigned*)ticket.output;p.instances=(OptixInstance*)instances;p.unit=unitHandle;p.capacity=capacity;p.nonce=nonce;p.epoch=epoch;p.generation=generation;p.horizon=.1f;p.scene=scene;p.terrainCapacity=terrainCapacity;p.probes=probes;p.kind=kind;p.members=members;p.terrainCount=terrain;
        CUDA_EXTERNAL_SEMAPHORE_WAIT_PARAMS wait{};wait.params.fence.value=ticket.value;checked(cuEventRecord(marks[0],stream));checked(cuWaitExternalSemaphoresAsync(&ticket.inputReady,&wait,1,stream));checked(cuEventRecord(marks[1],stream));
        unsigned terrainInstances=terrain,dynamicInstances=kind==0?members:0;
        if(snapshot){
            if(snapshot->scene!=scene||snapshot->rows.size()!=terrain)throw std::runtime_error("Terrain snapshot ownership mismatch");
            terrainInstances=publishTerrain(slot,*snapshot,p,dynamicInstances);
        }
        unsigned prepareRows=members+(snapshot?0u:terrain);
        void* args[]={&p};checked(cuLaunchKernel(validate,1,1,1,1,1,1,0,stream,args,nullptr));checked(cuLaunchKernel(prepare,std::max(1u,(prepareRows+255)/256),1,1,256,1,1,0,stream,args,nullptr));checked(cuEventRecord(marks[2],stream));
        buildIas(p,dynamicInstances+terrainInstances);checked(cuEventRecord(marks[3],stream));trace(p);checked(cuEventRecord(marks[4],stream));checked(cuLaunchKernel(seal,1,1,1,1,1,1,0,stream,args,nullptr));
        checked(cuMemcpyDtoHAsync(receipts+slot,ticket.output+11*sizeof(unsigned),sizeof(unsigned),stream));
        CUDA_EXTERNAL_SEMAPHORE_SIGNAL_PARAMS done{};done.params.fence.value=ticket.value;checked(cuSignalExternalSemaphoresAsync(&ticket.outputReady,&done,1,stream));checked(cuEventRecord(marks[5],stream));
    }
    ~Engine(){release();}
    void release(){if(context)cuCtxSetCurrent(context);if(stream)cuStreamSynchronize(stream);terrainCache.reset();if(pipeline)optixPipelineDestroy(pipeline);for(auto g:groups)if(g)optixProgramGroupDestroy(g);if(module)optixModuleDestroy(module);if(grid)cuModuleUnload(grid);if(optix)optixDeviceContextDestroy(optix);for(auto p:{vertices,indices,unitGas,instances,gas,scratch,params,records,temporary,stormHints})if(p)cuMemFree(p);particleRtOwnedBytes-=owned;if(host)cuMemFreeHost(host);if(receipts)cuMemFreeHost(receipts);if(stream&&!borrowedStream)cuStreamDestroy(stream);if(context)releaseContext();}
};
struct Job {std::shared_ptr<const TerrainSnapshot> terrain;int state{};bool launched{},drainRecorded{};BridgeTicket ticket{};uint64_t nonce{},generation{},scene{};unsigned kind{},members{},terrainCount{};CUevent marks[6]{},drain{};std::string error;};
struct Session {
    SRWLOCK lock=SRWLOCK_INIT;std::array<Job,3> jobs;std::shared_ptr<const TerrainSnapshot> terrainSnapshot;void* bridge{};std::unique_ptr<Engine> engine;uint64_t epoch{},lastSubmitted{};HANDLE notify{},thread{};bool closing{};unsigned delay{};std::string firstFailure;
    void failure(unsigned i,const std::string& phase,const std::string& reason){if(!firstFailure.empty())return;std::ostringstream out;auto& j=jobs[i];out<<"slot="<<i<<" task="<<j.nonce<<" epoch="<<epoch<<" source="<<j.generation<<" scene="<<j.scene<<" phase="<<phase<<" state="<<j.state<<" launched="<<j.launched<<" reason="<<reason;firstFailure=out.str();}
    std::string diagnostics(){Guard held(lock);std::ostringstream out;out<<"epoch="<<epoch<<" closing="<<closing<<" first_failure={"<<firstFailure<<"}";for(unsigned i=0;i<3;i++)out<<" slot="<<i<<"/task="<<jobs[i].nonce<<"/state="<<jobs[i].state<<"/launched="<<jobs[i].launched;return out.str();}
    Session(void* b,unsigned cap,unsigned terrain,unsigned rays,uint64_t ep,bool reference=false):bridge(b),epoch(ep){
        validateBridgeCapacity(bridge,128+size_t(cap+terrain)*64,3);acquireBridgeLease(bridge);try{engine=std::make_unique<Engine>(cap,terrain,rays,reference);for(auto& j:jobs){for(auto& m:j.marks)checked(cuEventCreate(&m,0));checked(cuEventCreate(&j.drain,CU_EVENT_DISABLE_TIMING));}notify=CreateEventW(nullptr,FALSE,FALSE,nullptr);if(!notify)throw std::runtime_error("RT worker event unavailable");thread=(HANDLE)_beginthreadex(nullptr,0,entry,this,0,nullptr);if(!thread)throw std::runtime_error("RT worker unavailable");}catch(...){releaseEvents();if(notify)CloseHandle(notify);engine.reset();releaseBridgeLease(bridge);throw;}
    }
    void releaseEvents(){for(auto& j:jobs){for(auto m:j.marks)if(m)cuEventDestroy(m);if(j.drain)cuEventDestroy(j.drain);}}
    static unsigned __stdcall entry(void* p){auto s=(Session*)p;try{s->work();}catch(const std::exception& e){Guard held(s->lock);for(unsigned i=0;i<s->jobs.size();i++)if(s->jobs[i].state==1)s->completeFailure(i,e.what());}return 0;}
    // A partial enqueue may still reference the shared GL/CUDA allocation.
    // Use the slot's drain event, and keep it quarantined if the driver cannot record one.
    void completeFailure(unsigned i,const char* reason){
        auto& j=jobs[i];
        j.drainRecorded=j.launched&&cuEventRecord(j.drain,engine->stream)==CUDA_SUCCESS;
        j.state=4;j.error=reason;failure(i,"enqueue-or-completion",reason);
    }
    bool drained(const Job& j){return !j.launched||(j.drainRecorded&&cuCtxSetCurrent(engine->context)==CUDA_SUCCESS&&cuEventQuery(j.drain)==CUDA_SUCCESS);}
    static void CUDA_CB delayed(void* p){Sleep(DWORD(uintptr_t(p)));}
    void work(){checked(cuCtxSetCurrent(engine->context));for(;;){bool pending=false;std::array<std::pair<uint64_t,unsigned>,3> order{};unsigned count=0;
        {Guard held(lock);
            for(unsigned i=0;i<jobs.size();i++)if(jobs[i].state==1)order[count++]={jobs[i].nonce,i};
            std::sort(order.begin(),order.begin()+count);
        }
        // A new submission may arrive during this loop. It belongs to the
        // next snapshot of the ordered queue, even if it reused a lower slot.
        for(unsigned k=0;k<count;k++){auto [nonce,i]=order[k];
        Job snapshot;unsigned ms=0;{Guard held(lock);if(jobs[i].state!=1||jobs[i].nonce!=nonce)continue;
          if(!firstFailure.empty()&&!jobs[i].launched){jobs[i].state=4;jobs[i].error="Cancelled after prior native failure: "+firstFailure;continue;}
          snapshot=jobs[i];if(!snapshot.launched){jobs[i].launched=true;ms=delay;delay=0;}pending=true;}
        try{if(!snapshot.launched){if(ms)checked(cuLaunchHostFunc(engine->stream,delayed,(void*)uintptr_t(ms)));engine->enqueue(i,snapshot.ticket,snapshot.nonce,epoch,snapshot.generation,snapshot.scene,snapshot.kind,snapshot.members,snapshot.terrainCount,snapshot.terrain.get(),jobs[i].marks);}else{auto status=cuEventQuery(snapshot.marks[5]);if(status==CUDA_SUCCESS){Guard held(lock);jobs[i].state=2;if(engine->receipts[i])failure(i,"producer-receipt",std::to_string(engine->receipts[i]));}else if(status!=CUDA_ERROR_NOT_READY)checked(status);}}
        catch(const std::exception& e){Guard held(lock);completeFailure(i,e.what());}
      }{Guard held(lock);if(closing&&!pending)return;}WaitForSingleObject(notify,pending?1:10);}}
    int poll(unsigned i,uint64_t nonce){Guard held(lock);auto& j=jobs.at(i);if(j.nonce!=nonce)throw std::runtime_error("Spatial ticket mismatch");return j.state;}
    bool submit(unsigned i,uint64_t nonce,uint64_t generation,uint64_t scene,unsigned kind,unsigned members,unsigned terrain){
        if(kind>1||members>engine->capacity||terrain>engine->terrainCapacity)throw std::runtime_error("Spatial input exceeds initialized contract");
        {Guard held(lock);if(i>=3||closing||!firstFailure.empty()||nonce<=lastSubmitted)return false;if(jobs[i].state)return false;jobs[i].state=6;lastSubmitted=nonce;}
        BridgeTicket ticket;try{ticket=releaseQueryInput(bridge,i);}catch(...){Guard held(lock);jobs[i].state=0;throw;}
        {Guard held(lock);auto& j=jobs[i];j.ticket=ticket;j.nonce=nonce;j.generation=generation;j.scene=scene;j.kind=kind;j.members=members;j.terrainCount=terrain;j.terrain=terrainSnapshot;j.launched=false;j.drainRecorded=false;j.error.clear();j.state=1;}SetEvent(notify);return true;
    }
    int consume(unsigned i,uint64_t nonce){
        int state=poll(i,nonce);{Guard held(lock);for(const auto& older:jobs)if(older.nonce<nonce&&(older.state==1||older.state==2||older.state==4||older.state==6))throw std::runtime_error("RT results must be consumed in submission order");}if(state!=2&&state!=4)throw std::runtime_error("Spatial result not complete");
        int receipt=state==4?4:int(engine->receipts[i]);
        if(receipt){Guard held(lock);failure(i,"producer-receipt",std::to_string(receipt));}
        if(state==4){
            if(!drained(jobs[i])){Guard held(lock);jobs[i].state=7;return receipt;}
            retireQueryWithoutOutput(bridge,i,jobs[i].ticket.value);
        }else consumeQueryOutput(bridge,i,jobs[i].ticket.value);
        Guard held(lock);jobs[i].state=5;return receipt;
    }
    bool recycle(unsigned i,uint64_t nonce,void* fence){int state=poll(i,nonce);{Guard held(lock);for(const auto& older:jobs)if(older.state&&older.nonce<nonce)return false;}if(state==7){if(!drained(jobs[i]))return false;retireQueryWithoutOutput(bridge,i,jobs[i].ticket.value);{Guard held(lock);jobs[i].state=5;}state=5;}if(state==5){if(!fence)return false;using Wait=unsigned(APIENTRY*)(void*,unsigned,unsigned long long);auto wait=(Wait)wglGetProcAddress("glClientWaitSync");if(!wait)throw std::runtime_error("GL retirement unavailable");auto status=wait(fence,1,0);if(status==0x911B)return false;if(status!=0x911A&&status!=0x911C)throw std::runtime_error("Spatial fence failed");}else return false;Guard held(lock);jobs[i].terrain.reset();jobs[i].state=0;return true;}
    bool close(){
        {Guard held(lock);closing=true;for(auto& j:jobs)if(j.state!=0)return false;}SetEvent(notify);if(WaitForSingleObject(thread,0)!=WAIT_OBJECT_0)return false;checked(cuCtxSetCurrent(engine->context));if(cuStreamQuery(engine->stream)!=CUDA_SUCCESS)return false;
        releaseEvents();engine.reset();CloseHandle(thread);CloseHandle(notify);releaseBridgeLease(bridge);return true;
    }
};
}
struct StormSpatial {
    Engine pipeline;
    StormSpatial(unsigned capacity,unsigned probes,CUstream stream):
        pipeline(capacity,CMI_RESOURCE_STORM_SPATIAL_TERRAIN_LIMIT,probes,false,stream){}
};
StormSpatial* createStormSpatial(unsigned capacity,unsigned probes,CUstream stream){
    return new StormSpatial(capacity,probes,stream);
}
void destroyStormSpatial(StormSpatial* spatial){delete spatial;}
CUdeviceptr queryStormSpatial(StormSpatial& spatial,unsigned slot,CUfunction prepare,
        CUdeviceptr members,const StormTick& tick,const TerrainSnapshot* terrain,const CUevent* timing){
    auto& engine=spatial.pipeline;
    auto& p=engine.host[slot];
    p={};
    p.instances=reinterpret_cast<OptixInstance*>(engine.instances);
    p.unit=engine.unitHandle;
    p.capacity=engine.capacity;p.members=tick.population;p.probes=engine.probes;
    p.epoch=tick.identity;p.generation=tick.tick==0?0:tick.tick-1;
    p.stormMembers=reinterpret_cast<const StormStepMember*>(members);
    p.stormHints=reinterpret_cast<float4*>(engine.stormHints);
    unsigned staticInstances=terrain?engine.publishTerrain(slot,*terrain,p,tick.population):0;
    void* args[]={&p};
    checked(cuLaunchKernel(prepare,(p.members+255)/256,1,1,256,1,1,0,engine.stream,args,nullptr));
    if(timing)checked(cuEventRecord(timing[0],engine.stream));
    engine.buildIas(p,p.members+staticInstances);
    if(timing)checked(cuEventRecord(timing[1],engine.stream));
    engine.trace(p);
    if(timing)checked(cuEventRecord(timing[2],engine.stream));
    return engine.stormHints;
}
std::shared_ptr<const TerrainSnapshot> copyTerrainSnapshot(uint64_t scene,uint64_t revision,
        const SpatialParticle* data,int count,size_t bytes,unsigned capacity){
    if(count<0||unsigned(count)>capacity||bytes<size_t(count)*sizeof(SpatialParticle)||(count&&!data))
        throw std::runtime_error("Invalid native terrain input range");
    auto snapshot=std::make_shared<TerrainSnapshot>();
    snapshot->scene=scene;snapshot->revision=revision;
    if(count)snapshot->rows.assign(data,data+count);
    for(const auto& row:snapshot->rows){
        float center[]={row.position.x,row.position.y,row.position.z},half[]={row.velocity.x,row.velocity.y,row.velocity.z};
        for(unsigned axis=0;axis<3;axis++)if(!std::isfinite(center[axis])||std::abs(center[axis])>31000000.f
            ||!std::isfinite(half[axis])||half[axis]<=0||half[axis]>8)
            throw std::runtime_error("Invalid native terrain geometry");
        if((row.identity.w&3u)==0u||(row.identity.w&~3u)!=0u)
            throw std::runtime_error("Invalid native terrain masks");
    }
    return snapshot;
}
extern "C" JNIEXPORT jint JNICALL JNI_NAME(abi)(JNIEnv*,jclass){return CMI_RT_ABI;}
extern "C" JNIEXPORT void JNICALL JNI_NAME(terrain)(JNIEnv* e,jclass,jlong h,jlong scene,jlong revision,jobject rows,jint count){
    try{
        auto s=reinterpret_cast<Session*>(h);auto bytes=e->GetDirectBufferCapacity(rows);auto data=static_cast<const SpatialParticle*>(e->GetDirectBufferAddress(rows));
        auto snapshot=copyTerrainSnapshot(scene,revision,data,count,size_t(std::max<jlong>(0,bytes)),s->engine->terrainCapacity);
        Guard held(s->lock);if(s->closing)throw std::runtime_error("Terrain submitted to closing RT worker");s->terrainSnapshot=std::move(snapshot);
    }catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT jstring JNICALL JNI_NAME(version)(JNIEnv* e,jclass){return e->NewStringUTF(CMI_RT_VERSION);}
extern "C" JNIEXPORT jlong JNICALL JNI_NAME(ownedBytes)(JNIEnv*,jclass){return jlong(particleRtOwnedBytes.load());}
extern "C" JNIEXPORT jlong JNICALL JNI_NAME(create)(JNIEnv* e,jclass,jlong bridge,jint cap,jint terrain,jint rays,jlong epoch,jstring){try{if(cap<1||cap>131072||terrain<0||terrain>65536||(rays!=2&&rays!=4)||!epoch)throw std::runtime_error("Bounded scene descriptor required");return (jlong)new Session((void*)bridge,cap,terrain,rays,epoch);}catch(const std::exception& x){fail(e,x);return 0;}}
extern "C" JNIEXPORT jlong JNICALL JNI_NAME(createReference)(JNIEnv* e,jclass,jlong bridge,jint cap,jint terrain,jint rays,jlong epoch,jstring){try{if(cap<1||cap>131072||terrain<0||terrain>65536||(rays!=2&&rays!=4)||!epoch)throw std::runtime_error("Bounded reference descriptor required");return (jlong)new Session((void*)bridge,cap,terrain,rays,epoch,true);}catch(const std::exception& x){fail(e,x);return 0;}}
extern "C" JNIEXPORT jboolean JNICALL JNI_NAME(submit)(JNIEnv* e,jclass,jlong h,jint slot,jlong nonce,jlong generation,jlong scene,jint kind){try{if(kind<0||kind>1)throw std::runtime_error("Query kind");auto s=(Session*)h;return s->submit(slot,nonce,generation,scene,kind,s->engine->capacity,s->engine->terrainCapacity);}catch(const std::exception& x){fail(e,x);return false;}}
extern "C" JNIEXPORT jboolean JNICALL JNI_NAME(submitRange)(JNIEnv* e,jclass,jlong h,jint slot,jlong nonce,jlong generation,jlong scene,jint kind,jint members,jint terrain){try{if(kind<0||kind>1||members<0||terrain<0)throw std::runtime_error("Query range/kind");return ((Session*)h)->submit(slot,nonce,generation,scene,kind,members,terrain);}catch(const std::exception& x){fail(e,x);return false;}}
extern "C" JNIEXPORT jint JNICALL JNI_NAME(poll)(JNIEnv* e,jclass,jlong h,jint slot,jlong nonce){try{return ((Session*)h)->poll(slot,nonce);}catch(const std::exception& x){fail(e,x);return 4;}}
extern "C" JNIEXPORT jint JNICALL JNI_NAME(consume)(JNIEnv* e,jclass,jlong h,jint slot,jlong nonce){try{return ((Session*)h)->consume(slot,nonce);}catch(const std::exception& x){fail(e,x);return 4;}}
extern "C" JNIEXPORT jboolean JNICALL JNI_NAME(recycle)(JNIEnv* e,jclass,jlong h,jint slot,jlong nonce,jlong fence){try{return ((Session*)h)->recycle(slot,nonce,(void*)fence);}catch(const std::exception& x){fail(e,x);return false;}}
extern "C" JNIEXPORT jboolean JNICALL JNI_NAME(close)(JNIEnv* e,jclass,jlong h){try{auto s=(Session*)h;if(!s->close())return false;delete s;return true;}catch(const std::exception& x){fail(e,x);return false;}}
extern "C" JNIEXPORT void JNICALL JNI_NAME(delay)(JNIEnv* e,jclass,jlong h,jint ms){try{if(ms<0||ms>500)throw std::runtime_error("Bounded delay required");auto s=(Session*)h;Guard held(s->lock);s->delay=ms;}catch(const std::exception& x){fail(e,x);}}
extern "C" JNIEXPORT jdoubleArray JNICALL JNI_NAME(timing)(JNIEnv* e,jclass,jlong h,jint slot,jlong nonce){try{auto s=(Session*)h;int state=s->poll(slot,nonce);if(state!=2&&state!=5&&state!=7)throw std::runtime_error("Timing requires completed or consumed spatial");double values[5]{};for(unsigned i=0;i<5&&s->jobs[slot].error.empty();i++){float ms;checked(cuEventElapsedTime(&ms,s->jobs[slot].marks[i],s->jobs[slot].marks[i+1]));values[i]=ms;}auto a=e->NewDoubleArray(5);e->SetDoubleArrayRegion(a,0,5,values);return a;}catch(const std::exception& x){fail(e,x);return nullptr;}}
extern "C" JNIEXPORT jstring JNICALL JNI_NAME(capabilities)(JNIEnv* e,jclass){return e->NewStringUTF("{\"abi\":12,\"packet_header_bytes\":128,\"input_stride\":64,\"output_stride\":64,\"probes\":[2,4],\"horizon\":0.1,\"primitive\":\"opaque-triangle-box\",\"precompiled\":true,\"cpu_envelope_readback\":false}");}
extern "C" JNIEXPORT jstring JNICALL JNI_NAME(diagnostics)(JNIEnv* e,jclass,jlong h){try{return e->NewStringUTF(((Session*)h)->diagnostics().c_str());}catch(const std::exception& x){fail(e,x);return nullptr;}}
