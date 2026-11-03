#include "Common.h"
#include "AsyncApi.h"
#include "Avoidance.h"
#include <optix_stubs.h>
#include <optix_stack_size.h>
#include <process.h>
#include <vector>
#include <memory>
#include <array>
#include <chrono>
#include <algorithm>
#include <sstream>

extern std::string compileOffload(const std::string&,const std::vector<std::string>&,int,int);
namespace {
void rtChecked(OptixResult r,const char* call){if(r!=OPTIX_SUCCESS)throw std::runtime_error(std::string(call)+": "+optixGetErrorName(r));}
#define rt(call) rtChecked(call,#call)
struct Guard {SRWLOCK& lock;Guard(SRWLOCK& l):lock(l){AcquireSRWLockExclusive(&lock);}~Guard(){ReleaseSRWLockExclusive(&lock);}};
struct Engine {
    CUcontext context{};CUstream stream{};OptixDeviceContext optix{};CUmodule grid{};CUfunction validate{},prepare{},seal{};
    OptixModule module{};OptixProgramGroup groups[3]{};OptixPipeline pipeline{};OptixShaderBindingTable sbt{};
    CUdeviceptr vertices{},indices{},unitGas{},instances{},gas{},scratch{},params{},records{},temporary{};size_t owned{},gasBytes{},scratchBytes{};
    OptixTraversableHandle unitHandle{},handle{};unsigned capacity{};bool built{};AvoidanceParams* host{};
    void alloc(CUdeviceptr& p,size_t bytes){bytes=std::max<size_t>(bytes,16);reserveBytes(bytes);try{checked(cuMemAlloc(&p,bytes));}catch(...){offloadOwnedBytes-=bytes;throw;}owned+=bytes;}
    Engine(unsigned cap,const std::string& cuda,const std::string& optixRoot,const std::string& source,const std::string& crt):capacity(cap){
        try{context=acquireContext();checked(cuStreamCreate(&stream,CU_STREAM_NON_BLOCKING));rt(optixInit());OptixDeviceContextOptions opts{};rt(optixDeviceContextCreate(context,&opts,&optix));
        CUdevice device{};checked(cuCtxGetDevice(&device));int major{},minor{};checked(cuDeviceGetAttribute(&major,CU_DEVICE_ATTRIBUTE_COMPUTE_CAPABILITY_MAJOR,device));checked(cuDeviceGetAttribute(&minor,CU_DEVICE_ATTRIBUTE_COMPUTE_CAPABILITY_MINOR,device));
        std::vector<std::string> inc={cuda+"/include",crt+"/include",optixRoot+"/include",source};auto ptx=compileOffload(source+"/avoidance-grid.cu",inc,major,minor);checked(cuModuleLoadData(&grid,ptx.c_str()));checked(cuModuleGetFunction(&validate,grid,"validateAvoidance"));checked(cuModuleGetFunction(&prepare,grid,"prepareAvoidance"));checked(cuModuleGetFunction(&seal,grid,"sealAvoidance"));
        float v[]={-1,-1,-1,1,-1,-1,1,1,-1,-1,1,-1,-1,-1,1,1,-1,1,1,1,1,-1,1,1};
        unsigned ix[]={0,2,1,0,3,2,4,5,6,4,6,7,0,4,7,0,7,3,1,2,6,1,6,5,0,1,5,0,5,4,3,7,6,3,6,2};
        alloc(vertices,sizeof(v));alloc(indices,sizeof(ix));checked(cuMemcpyHtoD(vertices,v,sizeof(v)));checked(cuMemcpyHtoD(indices,ix,sizeof(ix)));
        unsigned flags=OPTIX_GEOMETRY_FLAG_DISABLE_ANYHIT;OptixBuildInput input{};input.type=OPTIX_BUILD_INPUT_TYPE_TRIANGLES;input.triangleArray.vertexBuffers=&vertices;input.triangleArray.numVertices=8;input.triangleArray.vertexFormat=OPTIX_VERTEX_FORMAT_FLOAT3;input.triangleArray.vertexStrideInBytes=12;input.triangleArray.indexBuffer=indices;input.triangleArray.numIndexTriplets=12;input.triangleArray.indexFormat=OPTIX_INDICES_FORMAT_UNSIGNED_INT3;input.triangleArray.indexStrideInBytes=12;input.triangleArray.flags=&flags;input.triangleArray.numSbtRecords=1;
        OptixAccelBuildOptions options{};options.buildFlags=OPTIX_BUILD_FLAG_PREFER_FAST_TRACE;options.operation=OPTIX_BUILD_OPERATION_BUILD;OptixAccelBufferSizes size{};rt(optixAccelComputeMemoryUsage(optix,&options,&input,1,&size));alloc(unitGas,size.outputSizeInBytes);alloc(temporary,size.tempSizeInBytes);
        rt(optixAccelBuild(optix,stream,&options,&input,1,temporary,size.tempSizeInBytes,unitGas,size.outputSizeInBytes,&unitHandle,nullptr,0));checked(cuStreamSynchronize(stream));cuMemFree(temporary);temporary=0;owned-=std::max<size_t>(16,size.tempSizeInBytes);offloadOwnedBytes-=std::max<size_t>(16,size.tempSizeInBytes);
        alloc(instances,cap*sizeof(OptixInstance));input={};input.type=OPTIX_BUILD_INPUT_TYPE_INSTANCES;input.instanceArray.instances=instances;input.instanceArray.numInstances=cap;options.buildFlags=OPTIX_BUILD_FLAG_ALLOW_UPDATE|OPTIX_BUILD_FLAG_PREFER_FAST_TRACE;rt(optixAccelComputeMemoryUsage(optix,&options,&input,1,&size));gasBytes=size.outputSizeInBytes;scratchBytes=std::max(size.tempSizeInBytes,size.tempUpdateSizeInBytes);alloc(gas,gasBytes);alloc(scratch,scratchBytes);alloc(params,sizeof(AvoidanceParams));checked(cuMemHostAlloc((void**)&host,sizeof(AvoidanceParams)*3,0));
        ptx=compileOffload(source+"/avoidance-rt.cu",inc,major,minor);OptixModuleCompileOptions mc{};mc.optLevel=OPTIX_COMPILE_OPTIMIZATION_DEFAULT;OptixPipelineCompileOptions pc{};pc.traversableGraphFlags=OPTIX_TRAVERSABLE_GRAPH_FLAG_ALLOW_SINGLE_LEVEL_INSTANCING;pc.numPayloadValues=5;pc.numAttributeValues=2;pc.pipelineLaunchParamsVariableName="params";pc.usesPrimitiveTypeFlags=OPTIX_PRIMITIVE_TYPE_FLAGS_TRIANGLE;char log[8192];size_t length=sizeof(log);
        auto status=optixModuleCreate(optix,&mc,&pc,ptx.data(),ptx.size(),log,&length,&module);if(status!=OPTIX_SUCCESS)throw std::runtime_error(log);
        OptixProgramGroupOptions po{};for(unsigned i=0;i<3;i++){OptixProgramGroupDesc d{};if(i==0){d.kind=OPTIX_PROGRAM_GROUP_KIND_RAYGEN;d.raygen.module=module;d.raygen.entryFunctionName="__raygen__avoidance";}else if(i==1){d.kind=OPTIX_PROGRAM_GROUP_KIND_MISS;d.miss.module=module;d.miss.entryFunctionName="__miss__avoidance";}else{d.kind=OPTIX_PROGRAM_GROUP_KIND_HITGROUP;d.hitgroup.moduleCH=module;d.hitgroup.entryFunctionNameCH="__closesthit__avoidance";}length=sizeof(log);status=optixProgramGroupCreate(optix,&d,1,&po,log,&length,&groups[i]);if(status!=OPTIX_SUCCESS)throw std::runtime_error(log);}
        OptixPipelineLinkOptions link{};link.maxTraceDepth=1;length=sizeof(log);rt(optixPipelineCreate(optix,&pc,&link,groups,3,log,&length,&pipeline));OptixStackSizes ss{};for(auto group:groups)rt(optixUtilAccumulateStackSizes(group,&ss,pipeline));unsigned a,b,c;rt(optixUtilComputeStackSizes(&ss,1,0,0,&a,&b,&c));rt(optixPipelineSetStackSize(pipeline,a,b,c,2));
        struct alignas(OPTIX_SBT_RECORD_ALIGNMENT) Record {char h[OPTIX_SBT_RECORD_HEADER_SIZE];};Record rs[3]{};for(unsigned i=0;i<3;i++)rt(optixSbtRecordPackHeader(groups[i],&rs[i]));alloc(records,sizeof(rs));checked(cuMemcpyHtoD(records,rs,sizeof(rs)));sbt.raygenRecord=records;sbt.missRecordBase=records+sizeof(Record);sbt.missRecordStrideInBytes=sizeof(Record);sbt.missRecordCount=1;sbt.hitgroupRecordBase=records+2*sizeof(Record);sbt.hitgroupRecordStrideInBytes=sizeof(Record);sbt.hitgroupRecordCount=1;
        }catch(...){release();throw;}
    }
    void enqueue(unsigned slot,const BridgeTicket& ticket,uint64_t nonce,uint64_t epoch,uint64_t generation,uint64_t scene,CUevent* marks){
        auto& p=host[slot];p={};p.input=(unsigned*)ticket.input;p.output=(unsigned*)ticket.output;p.instances=(OptixInstance*)instances;p.unit=unitHandle;p.capacity=capacity;p.nonce=nonce;p.epoch=epoch;p.generation=generation;p.horizon=.1f;p.scene=scene;
        CUDA_EXTERNAL_SEMAPHORE_WAIT_PARAMS wait{};wait.params.fence.value=ticket.value;checked(cuEventRecord(marks[0],stream));checked(cuWaitExternalSemaphoresAsync(&ticket.inputReady,&wait,1,stream));checked(cuEventRecord(marks[1],stream));
        void* args[]={&p};checked(cuLaunchKernel(validate,1,1,1,1,1,1,0,stream,args,nullptr));checked(cuLaunchKernel(prepare,(capacity+255)/256,1,1,256,1,1,0,stream,args,nullptr));checked(cuEventRecord(marks[2],stream));
        OptixBuildInput input{};input.type=OPTIX_BUILD_INPUT_TYPE_INSTANCES;input.instanceArray.instances=instances;input.instanceArray.numInstances=capacity;OptixAccelBuildOptions options{};options.buildFlags=OPTIX_BUILD_FLAG_ALLOW_UPDATE|OPTIX_BUILD_FLAG_PREFER_FAST_TRACE;options.operation=built?OPTIX_BUILD_OPERATION_UPDATE:OPTIX_BUILD_OPERATION_BUILD;
        rt(optixAccelBuild(optix,stream,&options,&input,1,scratch,scratchBytes,gas,gasBytes,&handle,nullptr,0));built=true;p.traversable=handle;checked(cuEventRecord(marks[3],stream));checked(cuMemcpyHtoDAsync(params,&p,sizeof(p),stream));rt(optixLaunch(pipeline,stream,params,sizeof(p),&sbt,capacity,1,1));checked(cuEventRecord(marks[4],stream));checked(cuLaunchKernel(seal,1,1,1,1,1,1,0,stream,args,nullptr));
        CUDA_EXTERNAL_SEMAPHORE_SIGNAL_PARAMS done{};done.params.fence.value=ticket.value;checked(cuSignalExternalSemaphoresAsync(&ticket.outputReady,&done,1,stream));checked(cuEventRecord(marks[5],stream));
    }
    ~Engine(){release();}
    void release(){if(context)cuCtxSetCurrent(context);if(stream)cuStreamSynchronize(stream);if(pipeline)optixPipelineDestroy(pipeline);for(auto g:groups)if(g)optixProgramGroupDestroy(g);if(module)optixModuleDestroy(module);if(grid)cuModuleUnload(grid);if(optix)optixDeviceContextDestroy(optix);for(auto p:{vertices,indices,unitGas,instances,gas,scratch,params,records,temporary})if(p)cuMemFree(p);offloadOwnedBytes-=owned;if(host)cuMemFreeHost(host);if(stream)cuStreamDestroy(stream);if(context)releaseContext();}
};
struct Job {int state{};bool launched{};BridgeTicket ticket{};uint64_t nonce{},generation{},scene{};CUevent marks[6]{};std::string error;};
struct Session {
    SRWLOCK lock=SRWLOCK_INIT;std::array<Job,3> jobs;void* bridge{};std::unique_ptr<Engine> engine;uint64_t epoch{};HANDLE notify{},thread{};bool closing{};unsigned delay{};std::string firstFailure;
    void failure(unsigned i,const std::string& phase,const std::string& reason){if(!firstFailure.empty())return;std::ostringstream out;auto& j=jobs[i];out<<"slot="<<i<<" task="<<j.nonce<<" epoch="<<epoch<<" source="<<j.generation<<" scene="<<j.scene<<" phase="<<phase<<" state="<<j.state<<" launched="<<j.launched<<" reason="<<reason;firstFailure=out.str();}
    std::string diagnostics(){Guard held(lock);std::ostringstream out;out<<"epoch="<<epoch<<" closing="<<closing<<" first_failure={"<<firstFailure<<"}";for(unsigned i=0;i<3;i++)out<<" slot="<<i<<"/task="<<jobs[i].nonce<<"/state="<<jobs[i].state<<"/launched="<<jobs[i].launched;return out.str();}
    Session(void* b,unsigned cap,uint64_t ep,const std::string& cuda,const std::string& optix,const std::string& source,const std::string& crt):bridge(b),epoch(ep){
        validateBridgeCapacity(bridge,128+size_t(cap)*64,3);acquireBridgeLease(bridge);try{engine=std::make_unique<Engine>(cap,cuda,optix,source,crt);for(auto& j:jobs)for(auto& m:j.marks)checked(cuEventCreate(&m,0));notify=CreateEventW(nullptr,FALSE,FALSE,nullptr);if(!notify)throw std::runtime_error("Avoidance worker event unavailable");thread=(HANDLE)_beginthreadex(nullptr,0,entry,this,0,nullptr);if(!thread)throw std::runtime_error("Avoidance worker unavailable");}catch(...){for(auto& j:jobs)for(auto m:j.marks)if(m)cuEventDestroy(m);if(notify)CloseHandle(notify);engine.reset();releaseBridgeLease(bridge);throw;}
    }
    static unsigned __stdcall entry(void* p){auto s=(Session*)p;try{s->work();}catch(const std::exception& e){Guard held(s->lock);for(auto& j:s->jobs)if(j.state==1){j.state=4;j.error=e.what();}}return 0;}
    static void CUDA_CB delayed(void* p){Sleep(DWORD(uintptr_t(p)));}
    void work(){checked(cuCtxSetCurrent(engine->context));for(;;){bool pending=false;for(unsigned i=0;i<3;i++){
        Job snapshot;unsigned ms=0;{Guard held(lock);if(jobs[i].state!=1)continue;snapshot=jobs[i];if(!snapshot.launched){jobs[i].launched=true;ms=delay;delay=0;}pending=true;}
        try{if(!snapshot.launched){if(ms)checked(cuLaunchHostFunc(engine->stream,delayed,(void*)uintptr_t(ms)));engine->enqueue(i,snapshot.ticket,snapshot.nonce,epoch,snapshot.generation,snapshot.scene,jobs[i].marks);}else{auto status=cuEventQuery(snapshot.marks[5]);if(status==CUDA_SUCCESS){Guard held(lock);jobs[i].state=2;}else if(status!=CUDA_ERROR_NOT_READY)checked(status);}}
        catch(const std::exception& e){Guard held(lock);jobs[i].state=4;jobs[i].error=e.what();failure(i,"enqueue-or-completion",e.what());}
      }{Guard held(lock);if(closing&&!pending)return;}WaitForSingleObject(notify,pending?1:10);}}
    int poll(unsigned i,uint64_t nonce){Guard held(lock);auto& j=jobs.at(i);if(j.nonce!=nonce)throw std::runtime_error("Avoidance ticket mismatch");return j.state;}
    bool submit(unsigned i,uint64_t nonce,uint64_t generation,uint64_t scene){
        {Guard held(lock);if(i>=3||closing||!nonce)return false;if(jobs[i].state){failure(i,"submit","unretired slot");return false;}jobs[i].state=6;}
        BridgeTicket ticket;try{ticket=releaseQueryInput(bridge,i);}catch(...){Guard held(lock);jobs[i].state=0;throw;}
        {Guard held(lock);auto& j=jobs[i];j.ticket=ticket;j.nonce=nonce;j.generation=generation;j.scene=scene;j.launched=false;j.error.clear();j.state=1;}SetEvent(notify);return true;
    }
    void consume(unsigned i,uint64_t nonce){if(poll(i,nonce)!=2)throw std::runtime_error("Avoidance result not complete");consumeQueryOutput(bridge,i,jobs[i].ticket.value);Guard held(lock);jobs[i].state=5;}
    bool recycle(unsigned i,uint64_t nonce,void* fence){int state=poll(i,nonce);if(state==5){if(!fence)return false;using Wait=unsigned(APIENTRY*)(void*,unsigned,unsigned long long);auto wait=(Wait)wglGetProcAddress("glClientWaitSync");if(!wait)throw std::runtime_error("GL retirement unavailable");auto status=wait(fence,1,0);if(status==0x911B)return false;if(status!=0x911A&&status!=0x911C)throw std::runtime_error("Avoidance fence failed");}else if(state==2)consumeQueryOutput(bridge,i,jobs[i].ticket.value);else return false;Guard held(lock);jobs[i].state=0;return true;}
    bool close(){
        {Guard held(lock);closing=true;for(auto& j:jobs)if(j.state==1||j.state==5||j.state==6)return false;}SetEvent(notify);if(WaitForSingleObject(thread,0)!=WAIT_OBJECT_0)return false;checked(cuCtxSetCurrent(engine->context));if(cuStreamQuery(engine->stream)!=CUDA_SUCCESS)return false;
        for(unsigned i=0;i<3;i++)if(jobs[i].state==2){consumeQueryOutput(bridge,i,jobs[i].ticket.value);jobs[i].state=0;}
        for(auto& j:jobs)for(auto m:j.marks)cuEventDestroy(m);engine.reset();CloseHandle(thread);CloseHandle(notify);releaseBridgeLease(bridge);return true;
    }
};
}
extern "C" JNIEXPORT jlong JNICALL JNI_NAME(createAvoidance)(JNIEnv* e,jclass,jlong bridge,jint cap,jlong epoch,jstring cuda,jstring optix,jstring source,jstring crt){try{if(cap<1||cap>131072||!epoch)throw std::runtime_error("Bounded avoidance capacity/epoch required");return (jlong)new Session((void*)bridge,cap,epoch,utf(e,cuda),utf(e,optix),utf(e,source),utf(e,crt));}catch(const std::exception& x){fail(e,x);return 0;}}
extern "C" JNIEXPORT jboolean JNICALL JNI_NAME(submitAvoidance)(JNIEnv* e,jclass,jlong h,jint slot,jlong nonce,jlong generation,jlong scene){try{return ((Session*)h)->submit(slot,nonce,generation,scene);}catch(const std::exception& x){fail(e,x);return false;}}
extern "C" JNIEXPORT jint JNICALL JNI_NAME(pollAvoidance)(JNIEnv* e,jclass,jlong h,jint slot,jlong nonce){try{return ((Session*)h)->poll(slot,nonce);}catch(const std::exception& x){fail(e,x);return 4;}}
extern "C" JNIEXPORT void JNICALL JNI_NAME(consumeAvoidance)(JNIEnv* e,jclass,jlong h,jint slot,jlong nonce){try{((Session*)h)->consume(slot,nonce);}catch(const std::exception& x){fail(e,x);}}
extern "C" JNIEXPORT jboolean JNICALL JNI_NAME(recycleAvoidance)(JNIEnv* e,jclass,jlong h,jint slot,jlong nonce,jlong fence){try{return ((Session*)h)->recycle(slot,nonce,(void*)fence);}catch(const std::exception& x){fail(e,x);return false;}}
extern "C" JNIEXPORT jboolean JNICALL JNI_NAME(closeAvoidance)(JNIEnv* e,jclass,jlong h){try{auto s=(Session*)h;if(!s->close())return false;delete s;return true;}catch(const std::exception& x){fail(e,x);return false;}}
extern "C" JNIEXPORT void JNICALL JNI_NAME(delayAvoidance)(JNIEnv* e,jclass,jlong h,jint ms){try{if(ms<0||ms>500)throw std::runtime_error("Bounded delay required");auto s=(Session*)h;Guard held(s->lock);s->delay=ms;}catch(const std::exception& x){fail(e,x);}}
extern "C" JNIEXPORT jdoubleArray JNICALL JNI_NAME(avoidanceTiming)(JNIEnv* e,jclass,jlong h,jint slot,jlong nonce){try{auto s=(Session*)h;int state=s->poll(slot,nonce);if(state!=2&&state!=5)throw std::runtime_error("Timing requires completed avoidance");double values[5]{};for(unsigned i=0;i<5;i++){float ms;checked(cuEventElapsedTime(&ms,s->jobs[slot].marks[i],s->jobs[slot].marks[i+1]));values[i]=ms;}auto a=e->NewDoubleArray(5);e->SetDoubleArrayRegion(a,0,5,values);return a;}catch(const std::exception& x){fail(e,x);return nullptr;}}
extern "C" JNIEXPORT jstring JNICALL JNI_NAME(avoidanceCapabilities)(JNIEnv* e,jclass){return e->NewStringUTF("{\"abi\":10,\"packet_header_bytes\":128,\"input_stride\":64,\"output_stride\":64,\"probes\":4,\"horizon\":0.1,\"primitive\":\"opaque-triangle-box\",\"cpu_envelope_readback\":false}");}
extern "C" JNIEXPORT jstring JNICALL JNI_NAME(avoidanceDiagnostics)(JNIEnv* e,jclass,jlong h){try{return e->NewStringUTF(((Session*)h)->diagnostics().c_str());}catch(const std::exception& x){fail(e,x);return nullptr;}}
