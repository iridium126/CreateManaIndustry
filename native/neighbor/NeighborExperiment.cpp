#include <windows.h>
#include <jni.h>
#include <cuda.h>
#include <cudaGL.h>
#include <vector_types.h>
#include <nvrtc.h>
#include <optix.h>
#include <optix_function_table_definition.h>
#include <optix_stubs.h>
#include <optix_stack_size.h>
#include <algorithm>
#include <fstream>
#include <iterator>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
#include "Query.h"

static void cuda(CUresult r){if(r!=CUDA_SUCCESS){const char* text=nullptr;cuGetErrorString(r,&text);throw std::runtime_error(text?text:"CUDA error");}}
static void rt(OptixResult r){if(r!=OPTIX_SUCCESS)throw std::runtime_error(optixGetErrorName(r));}
static std::string string(JNIEnv* env,jstring s){const char* c=env->GetStringUTFChars(s,nullptr);std::string out(c);env->ReleaseStringUTFChars(s,c);return out;}
static std::string file(const std::string& path){std::ifstream in(path,std::ios::binary);if(!in)throw std::runtime_error("Cannot read "+path);return {std::istreambuf_iterator<char>(in),{}};}
static std::string compile(const std::string& path,const std::vector<std::string>& includes,int major,int minor){
    auto text=file(path);nvrtcProgram program{};auto result=nvrtcCreateProgram(&program,text.c_str(),path.c_str(),0,nullptr,nullptr);
    if(result!=NVRTC_SUCCESS)throw std::runtime_error(nvrtcGetErrorString(result));
    std::vector<std::string> options={"--std=c++17","--gpu-architecture=compute_"+std::to_string(major)+std::to_string(minor),"--fmad=false"};
    for(auto& include:includes)options.push_back("--include-path="+include);
    std::vector<const char*> raw;for(auto& option:options)raw.push_back(option.c_str());
    result=nvrtcCompileProgram(program,(int)raw.size(),raw.data());
    if(result!=NVRTC_SUCCESS){size_t size;nvrtcGetProgramLogSize(program,&size);std::string log(size,'\0');nvrtcGetProgramLog(program,log.data());nvrtcDestroyProgram(&program);throw std::runtime_error(log);}
    size_t size;nvrtcGetPTXSize(program,&size);std::string ptx(size,'\0');nvrtcGetPTX(program,ptx.data());nvrtcDestroyProgram(&program);return ptx;
}
struct Experiment {
    CUcontext context{};CUstream stream{};CUgraphicsResource resources[2]{};CUmodule grid{};
    CUfunction build{},query{},scan{},scanSums{},addSums{},bounds{};CUevent begin{},end{};
    OptixDeviceContext optix{};OptixModule module{};OptixProgramGroup groups[3]{};OptixPipeline pipeline{};OptixShaderBindingTable sbt{};
    CUdeviceptr counts{},heads{},next{},sums{},aabbs{},scratch{},gas{},params{},records{};size_t scratchSize{},gasSize{};
    Query p{};unsigned blocks{},hashSize{};bool built=false;bool mapped=false;
    void alloc(CUdeviceptr& ptr,size_t size){cuda(cuMemAlloc(&ptr,std::max<size_t>(size,16)));}
    void initialize(int input,int output,unsigned n,unsigned q,unsigned capacity,float radius,const std::string& cudaRoot,const std::string& optixRoot,const std::string& source,const std::string& crtRoot){
        if(q>n||radius<=0||!n)throw std::runtime_error("Invalid experiment dimensions");
        cuda(cuInit(0));unsigned devices=0;CUdevice device{};cuda(cuGLGetDevices(&devices,&device,1,CU_GL_DEVICE_LIST_CURRENT_FRAME));if(devices!=1)throw std::runtime_error("One matching OpenGL/CUDA GPU required");
        cuda(cuCtxCreate(&context,0,device));cuda(cuStreamCreate(&stream,CU_STREAM_DEFAULT));cuda(cuEventCreate(&begin,0));cuda(cuEventCreate(&end,0));
        cuda(cuGraphicsGLRegisterBuffer(&resources[0],input,CU_GRAPHICS_REGISTER_FLAGS_READ_ONLY));cuda(cuGraphicsGLRegisterBuffer(&resources[1],output,CU_GRAPHICS_REGISTER_FLAGS_WRITE_DISCARD));
        p.n=n;p.q=q;p.capacity=capacity;p.radius=radius;blocks=(q+255)/256;hashSize=1;while(hashSize<n*2)hashSize*=2;p.hashMask=hashSize-1;
        alloc(counts,n*4ull);alloc(heads,hashSize*4ull);alloc(next,n*4ull);alloc(sums,blocks*8ull);alloc(aabbs,n*24ull);alloc(params,sizeof(Query));
        p.counts=(unsigned*)counts;p.heads=(int*)heads;p.next=(int*)next;
        int major,minor;cuda(cuDeviceGetAttribute(&major,CU_DEVICE_ATTRIBUTE_COMPUTE_CAPABILITY_MAJOR,device));cuda(cuDeviceGetAttribute(&minor,CU_DEVICE_ATTRIBUTE_COMPUTE_CAPABILITY_MINOR,device));
        auto ptx=compile(source+"/grid.cu",{cudaRoot+"/include",crtRoot+"/include",source},major,minor);cuda(cuModuleLoadData(&grid,ptx.c_str()));
        cuda(cuModuleGetFunction(&build,grid,"build"));cuda(cuModuleGetFunction(&query,grid,"query"));cuda(cuModuleGetFunction(&scan,grid,"scan"));cuda(cuModuleGetFunction(&scanSums,grid,"scanSums"));cuda(cuModuleGetFunction(&addSums,grid,"addSums"));cuda(cuModuleGetFunction(&bounds,grid,"bounds"));
        rt(optixInit());OptixDeviceContextOptions contextOptions{};rt(optixDeviceContextCreate(context,&contextOptions,&optix));
        auto rtPtx=compile(source+"/rt.cu",{cudaRoot+"/include",crtRoot+"/include",optixRoot+"/include",source},major,minor);
        OptixModuleCompileOptions mc{};mc.optLevel=OPTIX_COMPILE_OPTIMIZATION_DEFAULT;mc.debugLevel=OPTIX_COMPILE_DEBUG_LEVEL_NONE;
        OptixPipelineCompileOptions pc{};pc.traversableGraphFlags=OPTIX_TRAVERSABLE_GRAPH_FLAG_ALLOW_SINGLE_GAS;pc.numPayloadValues=1;pc.numAttributeValues=0;pc.pipelineLaunchParamsVariableName="params";pc.usesPrimitiveTypeFlags=OPTIX_PRIMITIVE_TYPE_FLAGS_CUSTOM;
        char log[8192];size_t logSize=sizeof(log);auto status=optixModuleCreate(optix,&mc,&pc,rtPtx.data(),rtPtx.size(),log,&logSize,&module);if(status!=OPTIX_SUCCESS)throw std::runtime_error(log);
        OptixProgramGroupOptions pgOptions{};
        for(int i=0;i<3;i++){OptixProgramGroupDesc desc{};desc.kind=i==0?OPTIX_PROGRAM_GROUP_KIND_RAYGEN:i==1?OPTIX_PROGRAM_GROUP_KIND_MISS:OPTIX_PROGRAM_GROUP_KIND_HITGROUP;
            if(i==0){desc.raygen.module=module;desc.raygen.entryFunctionName="__raygen__query";}else if(i==1){desc.miss.module=module;desc.miss.entryFunctionName="__miss__noop";}else{desc.hitgroup.moduleIS=module;desc.hitgroup.entryFunctionNameIS="__intersection__point";}
            logSize=sizeof(log);rt(optixProgramGroupCreate(optix,&desc,1,&pgOptions,log,&logSize,&groups[i]));}
        OptixPipelineLinkOptions link{};link.maxTraceDepth=1;logSize=sizeof(log);rt(optixPipelineCreate(optix,&pc,&link,groups,3,log,&logSize,&pipeline));
        OptixStackSizes sizes{};for(auto group:groups)rt(optixUtilAccumulateStackSizes(group,&sizes,pipeline));unsigned dcTraversal,dcState,continuation;rt(optixUtilComputeStackSizes(&sizes,1,0,0,&dcTraversal,&dcState,&continuation));rt(optixPipelineSetStackSize(pipeline,dcTraversal,dcState,continuation,1));
        struct alignas(OPTIX_SBT_RECORD_ALIGNMENT) Record{char header[OPTIX_SBT_RECORD_HEADER_SIZE];};Record hostRecords[3]{};
        for(int i=0;i<3;i++)rt(optixSbtRecordPackHeader(groups[i],&hostRecords[i]));alloc(records,sizeof(hostRecords));cuda(cuMemcpyHtoD(records,hostRecords,sizeof(hostRecords)));
        sbt.raygenRecord=records;sbt.missRecordBase=records+sizeof(Record);sbt.missRecordCount=1;sbt.missRecordStrideInBytes=sizeof(Record);sbt.hitgroupRecordBase=records+2*sizeof(Record);sbt.hitgroupRecordCount=1;sbt.hitgroupRecordStrideInBytes=sizeof(Record);
        unsigned flag=OPTIX_GEOMETRY_FLAG_NONE;OptixBuildInput inputDesc{};inputDesc.type=OPTIX_BUILD_INPUT_TYPE_CUSTOM_PRIMITIVES;inputDesc.customPrimitiveArray.aabbBuffers=&aabbs;inputDesc.customPrimitiveArray.numPrimitives=n;inputDesc.customPrimitiveArray.flags=&flag;inputDesc.customPrimitiveArray.numSbtRecords=1;
        OptixAccelBuildOptions options{};options.buildFlags=OPTIX_BUILD_FLAG_ALLOW_UPDATE|OPTIX_BUILD_FLAG_PREFER_FAST_TRACE;options.operation=OPTIX_BUILD_OPERATION_BUILD;OptixAccelBufferSizes mem{};rt(optixAccelComputeMemoryUsage(optix,&options,&inputDesc,1,&mem));
        scratchSize=std::max(mem.tempSizeInBytes,mem.tempUpdateSizeInBytes);gasSize=mem.outputSizeInBytes;alloc(scratch,scratchSize);alloc(gas,gasSize);
    }
    void launch(CUfunction f,unsigned groupsCount,void** args){if(groupsCount)cuda(cuLaunchKernel(f,groupsCount,1,1,256,1,1,0,stream,args,nullptr));}
    float run(int backend){
        cuda(cuCtxSetCurrent(context));cuda(cuGraphicsMapResources(2,resources,stream));mapped=true;
        CUdeviceptr input,output;size_t inputBytes,outputBytes;cuda(cuGraphicsResourceGetMappedPointer(&input,&inputBytes,resources[0]));cuda(cuGraphicsResourceGetMappedPointer(&output,&outputBytes,resources[1]));
        size_t idsOffset=(16+(p.q+1)*8ull+255)&~255ull;
        if(inputBytes<p.n*16ull||outputBytes<idsOffset+p.capacity*4ull)throw std::runtime_error("Interop buffer size changed");
        p.points=(const float4*)input;p.header=(unsigned long long*)output;p.offsets=(unsigned long long*)(output+16);p.ids=(unsigned*)(output+idsOffset);p.write=0;
        void* args[]={&p};void* sumArgs[]={&p,&sums};void* scanArgs[]={&p,&sums,&blocks};cuda(cuEventRecord(begin,stream));
        if(backend==0){cuda(cuMemsetD32Async(heads,0xffffffffu,hashSize,stream));launch(build,(p.n+255)/256,args);launch(query,blocks,args);}
        else{
            void* boundArgs[]={&p,&aabbs};launch(bounds,(p.n+255)/256,boundArgs);
            unsigned flag=OPTIX_GEOMETRY_FLAG_NONE;OptixBuildInput desc{};desc.type=OPTIX_BUILD_INPUT_TYPE_CUSTOM_PRIMITIVES;desc.customPrimitiveArray.aabbBuffers=&aabbs;desc.customPrimitiveArray.numPrimitives=p.n;desc.customPrimitiveArray.flags=&flag;desc.customPrimitiveArray.numSbtRecords=1;
            OptixAccelBuildOptions options{};options.buildFlags=OPTIX_BUILD_FLAG_ALLOW_UPDATE|OPTIX_BUILD_FLAG_PREFER_FAST_TRACE;options.operation=backend==2&&built?OPTIX_BUILD_OPERATION_UPDATE:OPTIX_BUILD_OPERATION_BUILD;
            rt(optixAccelBuild(optix,stream,&options,&desc,1,scratch,scratchSize,gas,gasSize,&p.traversable,nullptr,0));built=true;
            cuda(cuMemcpyHtoDAsync(params,&p,sizeof(p),stream));rt(optixLaunch(pipeline,stream,params,sizeof(p),&sbt,p.q,1,1));
        }
        launch(scan,blocks,sumArgs);launch(scanSums,1,scanArgs);launch(addSums,blocks,sumArgs);p.write=1;
        if(backend==0)launch(query,blocks,args);else{cuda(cuMemcpyHtoDAsync(params,&p,sizeof(p),stream));rt(optixLaunch(pipeline,stream,params,sizeof(p),&sbt,p.q,1,1));}
        cuda(cuEventRecord(end,stream));cuda(cuGraphicsUnmapResources(2,resources,stream));mapped=false;cuda(cuStreamSynchronize(stream));float ms;cuda(cuEventElapsedTime(&ms,begin,end));return ms;
    }
    ~Experiment(){
        if(context){cuCtxSetCurrent(context);if(mapped)cuGraphicsUnmapResources(2,resources,stream);if(stream)cuStreamSynchronize(stream);}
        if(pipeline)optixPipelineDestroy(pipeline);for(auto group:groups)if(group)optixProgramGroupDestroy(group);if(module)optixModuleDestroy(module);if(optix)optixDeviceContextDestroy(optix);
        for(auto resource:resources)if(resource)cuGraphicsUnregisterResource(resource);
        for(auto ptr:{counts,heads,next,sums,aabbs,scratch,gas,params,records})if(ptr)cuMemFree(ptr);
        if(grid)cuModuleUnload(grid);if(begin)cuEventDestroy(begin);if(end)cuEventDestroy(end);if(stream)cuStreamDestroy(stream);if(context)cuCtxDestroy(context);
    }
};
static void failure(JNIEnv* env,const std::exception& error){env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),error.what());}
extern "C" JNIEXPORT jlong JNICALL Java_NeighborNative_create(JNIEnv* env,jclass,jint input,jint output,jint n,jint q,jint capacity,jfloat radius,jstring cudaRoot,jstring optixRoot,jstring source,jstring crtRoot){try{auto p=std::make_unique<Experiment>();p->initialize(input,output,n,q,capacity,radius,string(env,cudaRoot),string(env,optixRoot),string(env,source),string(env,crtRoot));return (jlong)p.release();}catch(const std::exception& e){failure(env,e);return 0;}}
extern "C" JNIEXPORT jfloat JNICALL Java_NeighborNative_run(JNIEnv* env,jclass,jlong handle,jint backend){try{return ((Experiment*)handle)->run(backend);}catch(const std::exception& e){failure(env,e);return 0;}}
extern "C" JNIEXPORT void JNICALL Java_NeighborNative_destroy(JNIEnv*,jclass,jlong handle){delete (Experiment*)handle;}
extern "C" JNIEXPORT jlongArray JNICALL Java_NeighborNative_memory(JNIEnv* env,jclass,jlong handle){
    try{auto p=(Experiment*)handle;cuda(cuCtxSetCurrent(p->context));size_t freeBytes,totalBytes;cuda(cuMemGetInfo(&freeBytes,&totalBytes));
        jlong values[]={(jlong)p->scratchSize,(jlong)p->gasSize,(jlong)(p->p.n*32ull+p->hashSize*4ull+p->blocks*8ull+sizeof(Query)+3*OPTIX_SBT_RECORD_HEADER_SIZE),(jlong)freeBytes,(jlong)totalBytes};
        auto result=env->NewLongArray(5);env->SetLongArrayRegion(result,0,5,values);return result;
    }catch(const std::exception& e){failure(env,e);return nullptr;}
}
