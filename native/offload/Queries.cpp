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
#include "Common.h"
#include "AsyncApi.h"
#include <array>
#include <chrono>
#include <cmath>

static void cuda(CUresult r){if(r!=CUDA_SUCCESS){const char* text=nullptr;cuGetErrorString(r,&text);throw std::runtime_error(text?text:"CUDA error");}}
static void rt(OptixResult r){if(r!=OPTIX_SUCCESS)throw std::runtime_error(optixGetErrorName(r));}
static std::string string(JNIEnv* env,jstring s){const char* c=env->GetStringUTFChars(s,nullptr);std::string out(c);env->ReleaseStringUTFChars(s,c);return out;}
static std::string file(const std::string& path){std::ifstream in(path,std::ios::binary);if(!in)throw std::runtime_error("Cannot read "+path);return {std::istreambuf_iterator<char>(in),{}};}
static std::string compile(const std::string& path,const std::vector<std::string>& includes,int major,int minor){
    // PTX is evidence, not a cache key: Query.h, options and SDKs can change.
    auto text=file(path);nvrtcProgram program{};auto result=nvrtcCreateProgram(&program,text.c_str(),path.c_str(),0,nullptr,nullptr);
    if(result!=NVRTC_SUCCESS)throw std::runtime_error(nvrtcGetErrorString(result));
    std::vector<std::string> options={"--std=c++17","--gpu-architecture=compute_"+std::to_string(major)+std::to_string(minor),"--fmad=false"};
    for(auto& include:includes)options.push_back("--include-path="+include);
    std::vector<const char*> raw;for(auto& option:options)raw.push_back(option.c_str());
    result=nvrtcCompileProgram(program,(int)raw.size(),raw.data());
    if(result!=NVRTC_SUCCESS){size_t size;nvrtcGetProgramLogSize(program,&size);std::string log(size,'\0');nvrtcGetProgramLog(program,log.data());nvrtcDestroyProgram(&program);throw std::runtime_error(log);}
    size_t size;nvrtcGetPTXSize(program,&size);std::string ptx(size,'\0');nvrtcGetPTX(program,ptx.data());nvrtcDestroyProgram(&program);std::ofstream(path+".ptx",std::ios::binary).write(ptx.data(),ptx.size());return ptx;
}
std::string compileOffload(const std::string& path,const std::vector<std::string>& includes,int major,int minor){return compile(path,includes,major,minor);}
struct Experiment {
    CUcontext context{};CUstream stream{};CUgraphicsResource resources[4]{};unsigned resourceCount=2;CUmodule grid{};
    CUfunction build{},query{},scan{},scanSums{},addSums{},bounds{},seal{},sortRows{},flattenPages{},checkCachedBounds{};unsigned nCapacity{},qCapacity{};CUevent begin{},end{};
    OptixDeviceContext optix{};OptixModule module{};OptixProgramGroup groups[3]{};OptixPipeline pipeline{};OptixShaderBindingTable sbt{};
    CUdeviceptr counts{},heads{},next{},sums{},aabbs{},scratch{},gas{},params{},records{};size_t scratchSize{},gasSize{};
    Query p{};Query* hostParams{};unsigned blocks{},hashSize{};bool built=false;bool mapped=false;bool externallyRetired=false;
    unsigned policy{},cachedN{};float cachedRadius{};CUdeviceptr cachedPoints{},cacheDirty{};
    size_t owned{};CUmodule tensorModule{};CUfunction tensorQuery{},scalarTile{},makeInstances{};CUevent marks[5]{},extra[3]{};std::array<double,7> last{};CUdeviceptr pageHeads{},pageTails{},pageCounter{},pages{};
    OptixModule triangleModule{};OptixProgramGroup triangleGroups[3]{};OptixPipeline trianglePipeline{};OptixShaderBindingTable triangleSbt{};CUdeviceptr unitVertices{},unitGas{},unitTemporary{},instanceData{},instanceGas{};OptixTraversableHandle unitHandle{};bool triangleBuilt=false;
    void alloc(CUdeviceptr& ptr,size_t size){size=std::max<size_t>(size,16);reserveBytes(size);auto status=cuMemAlloc(&ptr,size);if(status!=CUDA_SUCCESS){offloadOwnedBytes-=size;cuda(status);}owned+=size;}
    void initialize(int input,int output,unsigned n,unsigned q,unsigned capacity,float radius,const std::string& cudaRoot,const std::string& optixRoot,const std::string& source,const std::string& crtRoot,int requests=0,int owners=0,unsigned backendMask=31){
        if((q>n&&input!=0)||q>262144||radius<=0||!std::isfinite(radius)||n>131072)throw std::runtime_error("Invalid experiment dimensions");
        if(((16+(q+1)*8ull+255)&~255ull)+capacity*4ull>(64ull<<20))throw std::runtime_error("R7 candidate output exceeds 64 MiB slot; use complete reference query");
        cuda(cuInit(0));unsigned devices=0;CUdevice device{};cuda(cuGLGetDevices(&devices,&device,1,CU_GL_DEVICE_LIST_CURRENT_FRAME));if(devices!=1)throw std::runtime_error("One matching OpenGL/CUDA GPU required");
        context=acquireContext();cuda(cuStreamCreate(&stream,CU_STREAM_DEFAULT));cuda(cuMemHostAlloc((void**)&hostParams,2*sizeof(Query),0));cuda(cuEventCreate(&begin,0));cuda(cuEventCreate(&end,0));for(auto& mark:marks)cuda(cuEventCreate(&mark,0));for(auto& event:extra)cuda(cuEventCreate(&event,0));
        if(input&&output){cuda(cuGraphicsGLRegisterBuffer(&resources[0],input,CU_GRAPHICS_REGISTER_FLAGS_READ_ONLY));cuda(cuGraphicsGLRegisterBuffer(&resources[1],output,CU_GRAPHICS_REGISTER_FLAGS_WRITE_DISCARD));}else resourceCount=0;
        if(requests||owners){if(!requests||!owners)throw std::runtime_error("Both query request buffers required");resourceCount=4;cuda(cuGraphicsGLRegisterBuffer(&resources[2],requests,CU_GRAPHICS_REGISTER_FLAGS_READ_ONLY));cuda(cuGraphicsGLRegisterBuffer(&resources[3],owners,CU_GRAPHICS_REGISTER_FLAGS_READ_ONLY));}
        nCapacity=n;qCapacity=q;p.n=n;p.q=q;p.capacity=capacity;p.radius=radius;blocks=(q+255)/256;hashSize=1;while(hashSize<n*2)hashSize*=2;p.hashMask=hashSize-1;
        alloc(counts,std::max(n,q)*4ull);alloc(heads,hashSize*4ull);alloc(next,n*4ull);alloc(sums,blocks*8ull);alloc(aabbs,std::max(1u,n)*24ull);alloc(params,sizeof(Query));
        p.counts=(unsigned*)counts;p.heads=(int*)heads;p.next=(int*)next;
        int major,minor;cuda(cuDeviceGetAttribute(&major,CU_DEVICE_ATTRIBUTE_COMPUTE_CAPABILITY_MAJOR,device));cuda(cuDeviceGetAttribute(&minor,CU_DEVICE_ATTRIBUTE_COMPUTE_CAPABILITY_MINOR,device));
        auto ptx=compile(source+"/grid.cu",{cudaRoot+"/include",crtRoot+"/include",source},major,minor);cuda(cuModuleLoadData(&grid,ptx.c_str()));
        cuda(cuModuleGetFunction(&build,grid,"build"));cuda(cuModuleGetFunction(&query,grid,"query"));cuda(cuModuleGetFunction(&scan,grid,"scan"));cuda(cuModuleGetFunction(&scanSums,grid,"scanSums"));cuda(cuModuleGetFunction(&addSums,grid,"addSums"));cuda(cuModuleGetFunction(&bounds,grid,"bounds"));cuda(cuModuleGetFunction(&seal,grid,"sealResult"));cuda(cuModuleGetFunction(&sortRows,grid,"sortRows"));cuda(cuModuleGetFunction(&flattenPages,grid,"flattenPages"));cuda(cuModuleGetFunction(&checkCachedBounds,grid,"checkCachedBounds"));
        if(backendMask&6u){
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
        unsigned flag=OPTIX_GEOMETRY_FLAG_NONE;OptixBuildInput inputDesc{};inputDesc.type=OPTIX_BUILD_INPUT_TYPE_CUSTOM_PRIMITIVES;inputDesc.customPrimitiveArray.aabbBuffers=&aabbs;inputDesc.customPrimitiveArray.numPrimitives=std::max(1u,n);inputDesc.customPrimitiveArray.flags=&flag;inputDesc.customPrimitiveArray.numSbtRecords=1;
        OptixAccelBuildOptions options{};options.buildFlags=OPTIX_BUILD_FLAG_ALLOW_UPDATE|OPTIX_BUILD_FLAG_PREFER_FAST_TRACE;options.operation=OPTIX_BUILD_OPERATION_BUILD;OptixAccelBufferSizes mem{};rt(optixAccelComputeMemoryUsage(optix,&options,&inputDesc,1,&mem));
        scratchSize=std::max(mem.tempSizeInBytes,mem.tempUpdateSizeInBytes);gasSize=mem.outputSizeInBytes;alloc(scratch,scratchSize);alloc(gas,gasSize);
        }
        if(backendMask&28u){
        auto tensorPtx=compile(source+"/tensor.cu",{cudaRoot+"/include",crtRoot+"/include",optixRoot+"/include",source},major,minor);cuda(cuModuleLoadData(&tensorModule,tensorPtx.c_str()));cuda(cuModuleGetFunction(&tensorQuery,tensorModule,"tensorQuery"));cuda(cuModuleGetFunction(&scalarTile,tensorModule,"scalarTileQuery"));cuda(cuModuleGetFunction(&makeInstances,tensorModule,"instances"));
        }
        if(backendMask&4u)initializeTriangles(source,cudaRoot,crtRoot,optixRoot,major,minor);
    }
    void initializeTriangles(const std::string& source,const std::string& cudaRoot,const std::string& crtRoot,const std::string& optixRoot,int major,int minor){
        // A fixed +X ray needs only the box's exit face. One triangle strictly
        // covers [-1,1]^2 after outward inflation: no diagonal double hits.
        float vertices[]={1,-3,-1,1,1,-1,1,1,3};alloc(unitVertices,sizeof(vertices));cuda(cuMemcpyHtoD(unitVertices,vertices,sizeof(vertices)));
        unsigned flags=OPTIX_GEOMETRY_FLAG_REQUIRE_SINGLE_ANYHIT_CALL;OptixBuildInput unit{};unit.type=OPTIX_BUILD_INPUT_TYPE_TRIANGLES;unit.triangleArray.vertexBuffers=&unitVertices;unit.triangleArray.numVertices=3;unit.triangleArray.vertexFormat=OPTIX_VERTEX_FORMAT_FLOAT3;unit.triangleArray.vertexStrideInBytes=12;unit.triangleArray.flags=&flags;unit.triangleArray.numSbtRecords=1;
        OptixAccelBuildOptions options{};options.buildFlags=OPTIX_BUILD_FLAG_PREFER_FAST_TRACE;options.operation=OPTIX_BUILD_OPERATION_BUILD;OptixAccelBufferSizes memory{};rt(optixAccelComputeMemoryUsage(optix,&options,&unit,1,&memory));alloc(unitTemporary,memory.tempSizeInBytes);alloc(unitGas,memory.outputSizeInBytes);rt(optixAccelBuild(optix,stream,&options,&unit,1,unitTemporary,memory.tempSizeInBytes,unitGas,memory.outputSizeInBytes,&unitHandle,nullptr,0));cuda(cuStreamSynchronize(stream));cuda(cuMemFree(unitTemporary));unitTemporary=0;owned-=std::max<size_t>(memory.tempSizeInBytes,16);offloadOwnedBytes-=std::max<size_t>(memory.tempSizeInBytes,16);
        alloc(instanceData,std::max(1u,p.n)*sizeof(OptixInstance));OptixBuildInput input{};input.type=OPTIX_BUILD_INPUT_TYPE_INSTANCES;input.instanceArray.instances=instanceData;input.instanceArray.numInstances=std::max(1u,p.n);options.buildFlags=OPTIX_BUILD_FLAG_ALLOW_UPDATE|OPTIX_BUILD_FLAG_PREFER_FAST_TRACE;rt(optixAccelComputeMemoryUsage(optix,&options,&input,1,&memory));size_t need=std::max(memory.tempSizeInBytes,memory.tempUpdateSizeInBytes);if(need>scratchSize){cuda(cuMemFree(scratch));owned-=std::max<size_t>(scratchSize,16);offloadOwnedBytes-=std::max<size_t>(scratchSize,16);scratch=0;scratchSize=need;alloc(scratch,need);}alloc(instanceGas,memory.outputSizeInBytes);triangleGasBytes=memory.outputSizeInBytes;
        auto ptx=compile(source+"/rt-triangle.cu",{cudaRoot+"/include",crtRoot+"/include",optixRoot+"/include",source},major,minor);OptixModuleCompileOptions mc{};mc.optLevel=OPTIX_COMPILE_OPTIMIZATION_DEFAULT;OptixPipelineCompileOptions pc{};pc.traversableGraphFlags=OPTIX_TRAVERSABLE_GRAPH_FLAG_ALLOW_SINGLE_LEVEL_INSTANCING;pc.numPayloadValues=1;pc.pipelineLaunchParamsVariableName="params";pc.usesPrimitiveTypeFlags=OPTIX_PRIMITIVE_TYPE_FLAGS_TRIANGLE;
        char log[8192];size_t length=sizeof(log);auto status=optixModuleCreate(optix,&mc,&pc,ptx.data(),ptx.size(),log,&length,&triangleModule);if(status!=OPTIX_SUCCESS)throw std::runtime_error(log);
        OptixProgramGroupOptions groupOptions{};for(int i=0;i<3;i++){OptixProgramGroupDesc d{};d.kind=i==0?OPTIX_PROGRAM_GROUP_KIND_RAYGEN:i==1?OPTIX_PROGRAM_GROUP_KIND_MISS:OPTIX_PROGRAM_GROUP_KIND_HITGROUP;if(i==0){d.raygen.module=triangleModule;d.raygen.entryFunctionName="__raygen__query";}else if(i==1){d.miss.module=triangleModule;d.miss.entryFunctionName="__miss__noop";}else{d.hitgroup.moduleAH=triangleModule;d.hitgroup.entryFunctionNameAH="__anyhit__candidate";}length=sizeof(log);rt(optixProgramGroupCreate(optix,&d,1,&groupOptions,log,&length,&triangleGroups[i]));}
        OptixPipelineLinkOptions link{};link.maxTraceDepth=1;length=sizeof(log);rt(optixPipelineCreate(optix,&pc,&link,triangleGroups,3,log,&length,&trianglePipeline));OptixStackSizes sizes{};for(auto group:triangleGroups)rt(optixUtilAccumulateStackSizes(group,&sizes,trianglePipeline));unsigned a,b,c;rt(optixUtilComputeStackSizes(&sizes,1,0,0,&a,&b,&c));rt(optixPipelineSetStackSize(trianglePipeline,a,b,c,2));
        struct alignas(OPTIX_SBT_RECORD_ALIGNMENT) Record{char header[OPTIX_SBT_RECORD_HEADER_SIZE];};Record host[3]{};for(int i=0;i<3;i++)rt(optixSbtRecordPackHeader(triangleGroups[i],&host[i]));alloc(triangleRecords,sizeof(host));cuda(cuMemcpyHtoD(triangleRecords,host,sizeof(host)));triangleSbt.raygenRecord=triangleRecords;triangleSbt.missRecordBase=triangleRecords+sizeof(Record);triangleSbt.missRecordCount=1;triangleSbt.missRecordStrideInBytes=sizeof(Record);triangleSbt.hitgroupRecordBase=triangleRecords+sizeof(Record)*2;triangleSbt.hitgroupRecordCount=1;triangleSbt.hitgroupRecordStrideInBytes=sizeof(Record);
    }
    size_t triangleGasBytes{};CUdeviceptr triangleRecords{};
    void launch(CUfunction f,unsigned groupsCount,void** args){if(groupsCount)cuda(cuLaunchKernel(f,groupsCount,1,1,256,1,1,0,stream,args,nullptr));}
    float run(int backend,bool refit){
        auto wall=std::chrono::steady_clock::now();
        cuda(cuCtxSetCurrent(context));cuda(cuGraphicsMapResources(resourceCount,resources,stream));mapped=true;
        CUdeviceptr input,output;size_t inputBytes,outputBytes;cuda(cuGraphicsResourceGetMappedPointer(&input,&inputBytes,resources[0]));cuda(cuGraphicsResourceGetMappedPointer(&output,&outputBytes,resources[1]));
        size_t idsOffset=(16+(p.q+1)*8ull+255)&~255ull;
        if(inputBytes<p.n*16ull||outputBytes<idsOffset+p.capacity*4ull)throw std::runtime_error("Interop buffer size changed");
        p.points=(const float4*)input;p.header=(unsigned long long*)output;p.offsets=(unsigned long long*)(output+16);p.ids=(unsigned*)(output+idsOffset);p.write=0;
        if(resourceCount==4){CUdeviceptr requests,owners;size_t rb,ob;cuda(cuGraphicsResourceGetMappedPointer(&requests,&rb,resources[2]));cuda(cuGraphicsResourceGetMappedPointer(&owners,&ob,resources[3]));if(rb<p.q*16ull||ob<p.q*4ull)throw std::runtime_error("Short actual query request buffers");p.requests=(const float4*)requests;p.owners=(const unsigned*)owners;}
        if(!p.q){cuda(cuMemsetD8Async(output,0,24,stream));cuda(cuGraphicsUnmapResources(resourceCount,resources,stream));mapped=false;cuda(cuStreamSynchronize(stream));last.fill(0);last[1]=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-wall).count();return 0;}
        dispatchGpu(backend,refit);
        cuda(cuEventRecord(end,stream));cuda(cuGraphicsUnmapResources(resourceCount,resources,stream));mapped=false;cuda(cuStreamSynchronize(stream));float ms;cuda(cuEventElapsedTime(&ms,begin,end));last[0]=ms;last[1]=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-wall).count();for(int i=0;i<5;i++){cuda(cuEventElapsedTime(&ms,i?marks[i-1]:begin,marks[i]));last[i+2]=ms;}return float(last[0]);
    }
    void dispatchGpu(int backend,bool refit){
        if(!p.q){cuda(cuEventRecord(begin,stream));if(p.paged)cuda(cuMemsetD32Async(pageCounter,0,1,stream));cuda(cuMemsetD8Async((CUdeviceptr)p.header,0,24,stream));for(auto mark:marks)cuda(cuEventRecord(mark,stream));return;}
        void* args[]={&p};void* sumArgs[]={&p,&sums};void* scanArgs[]={&p,&sums,&blocks};cuda(cuEventRecord(begin,stream));
        auto queryPass=[&](){if(backend==0)launch(query,blocks,args);else if(backend==3||backend==4){cuda(cuLaunchKernel(backend==3?tensorQuery:scalarTile,(p.q+15)/16,1,1,32,1,1,0,stream,args,nullptr));}else{hostParams[p.write?1:0]=p;cuda(cuMemcpyHtoDAsync(params,hostParams+(p.write?1:0),sizeof(p),stream));rt(optixLaunch(backend==2?trianglePipeline:pipeline,stream,params,sizeof(p),backend==2?&triangleSbt:&sbt,p.q,1,1));}};
        if(backend==0||backend==3||backend==4){cuda(cuMemsetD32Async(heads,0xffffffffu,hashSize,stream));launch(build,(p.n+255)/256,args);cuda(cuEventRecord(marks[0],stream));cuda(cuEventRecord(marks[1],stream));}
        else if(backend==2){void* descArgs[]={&p,&instanceData,&unitHandle};launch(makeInstances,(p.n+255)/256,descArgs);cuda(cuEventRecord(marks[0],stream));OptixBuildInput desc{};desc.type=OPTIX_BUILD_INPUT_TYPE_INSTANCES;desc.instanceArray.instances=instanceData;desc.instanceArray.numInstances=p.n;OptixAccelBuildOptions options{};options.buildFlags=OPTIX_BUILD_FLAG_ALLOW_UPDATE|OPTIX_BUILD_FLAG_PREFER_FAST_TRACE;options.operation=refit&&triangleBuilt?OPTIX_BUILD_OPERATION_UPDATE:OPTIX_BUILD_OPERATION_BUILD;rt(optixAccelBuild(optix,stream,&options,&desc,1,scratch,scratchSize,instanceGas,triangleGasBytes,&p.traversable,nullptr,0));triangleBuilt=true;cuda(cuEventRecord(marks[1],stream));}
        else{
            bool reuse=false;
            if((policy&128u)&&built&&cachedN==p.n&&cachedRadius==p.radius){
                cuda(cuMemsetD32Async(cacheDirty,0,1,stream));void* checkArgs[]={&p,&aabbs,&cachedPoints,&cacheDirty};launch(checkCachedBounds,(p.n+255)/256,checkArgs);
                unsigned dirty=1;cuda(cuMemcpyDtoHAsync(&dirty,cacheDirty,4,stream));cuda(cuStreamSynchronize(stream));reuse=dirty==0;
            }
            void* boundArgs[]={&p,&aabbs};if(!reuse)launch(bounds,(p.n+255)/256,boundArgs);
            cuda(cuEventRecord(marks[0],stream));unsigned flag=OPTIX_GEOMETRY_FLAG_NONE;OptixBuildInput desc{};desc.type=OPTIX_BUILD_INPUT_TYPE_CUSTOM_PRIMITIVES;desc.customPrimitiveArray.aabbBuffers=&aabbs;desc.customPrimitiveArray.numPrimitives=p.n;desc.customPrimitiveArray.flags=&flag;desc.customPrimitiveArray.numSbtRecords=1;
            OptixAccelBuildOptions options{};options.buildFlags=OPTIX_BUILD_FLAG_ALLOW_UPDATE|OPTIX_BUILD_FLAG_PREFER_FAST_TRACE;options.operation=refit&&built?OPTIX_BUILD_OPERATION_UPDATE:OPTIX_BUILD_OPERATION_BUILD;
            if(!reuse){rt(optixAccelBuild(optix,stream,&options,&desc,1,scratch,scratchSize,gas,gasSize,&p.traversable,nullptr,0));built=true;
                if(policy&128u){cuda(cuMemcpyDtoDAsync(cachedPoints,(CUdeviceptr)p.points,p.n*16ull,stream));cachedN=p.n;cachedRadius=p.radius;}}
            cuda(cuEventRecord(marks[1],stream));
        }
        cuda(cuMemsetD8Async((CUdeviceptr)p.header,0,16,stream));if(p.paged){cuda(cuMemsetD32Async(pageCounter,std::min(p.q,p.pageCapacity),1,stream));cuda(cuMemsetD8Async(counts,0,p.q*4ull,stream));}
        if(p.fused){
            if(backend!=1)throw std::runtime_error("Fused separation requires RT AABB");
            cuda(cuMemsetD8Async((CUdeviceptr)p.forces,0,p.q*16ull,stream));queryPass();
            cuda(cuEventRecord(marks[2],stream));cuda(cuEventRecord(marks[3],stream));cuda(cuEventRecord(marks[4],stream));return;
        }
        queryPass();cuda(cuEventRecord(marks[2],stream));launch(scan,blocks,sumArgs);launch(scanSums,1,scanArgs);launch(addSums,blocks,sumArgs);cuda(cuEventRecord(marks[3],stream));p.write=1;if(p.paged)launch(flattenPages,blocks,args);else queryPass();cuda(cuEventRecord(marks[4],stream));
    }
    ~Experiment(){
        if(context){cuCtxSetCurrent(context);if(mapped)cuGraphicsUnmapResources(resourceCount,resources,stream);if(stream&&!externallyRetired)cuStreamSynchronize(stream);}
        if(trianglePipeline)optixPipelineDestroy(trianglePipeline);for(auto group:triangleGroups)if(group)optixProgramGroupDestroy(group);if(triangleModule)optixModuleDestroy(triangleModule);if(pipeline)optixPipelineDestroy(pipeline);for(auto group:groups)if(group)optixProgramGroupDestroy(group);if(module)optixModuleDestroy(module);if(optix)optixDeviceContextDestroy(optix);
        for(auto resource:resources)if(resource)cuGraphicsUnregisterResource(resource);
        for(auto ptr:{cachedPoints,cacheDirty,pageHeads,pageTails,pageCounter,pages,counts,heads,next,sums,aabbs,scratch,gas,params,records,unitVertices,unitGas,unitTemporary,instanceData,instanceGas,triangleRecords})if(ptr)cuMemFree(ptr);offloadOwnedBytes-=owned;
        if(hostParams)cuMemFreeHost(hostParams);if(tensorModule)cuModuleUnload(tensorModule);if(grid)cuModuleUnload(grid);for(auto event:marks)if(event)cuEventDestroy(event);for(auto event:extra)if(event)cuEventDestroy(event);if(begin)cuEventDestroy(begin);if(end)cuEventDestroy(end);if(stream)cuStreamDestroy(stream);if(context)releaseContext();
    }
};
static void failure(JNIEnv* env,const std::exception& error){env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),error.what());}
extern "C" JNIEXPORT jlong JNICALL JNI_NAME(createQuery)(JNIEnv* env,jclass,jint input,jint output,jint n,jint q,jint capacity,jfloat radius,jstring cudaRoot,jstring optixRoot,jstring source,jstring crtRoot){try{auto p=std::make_unique<Experiment>();p->initialize(input,output,n,q,capacity,radius,string(env,cudaRoot),string(env,optixRoot),string(env,source),string(env,crtRoot));return (jlong)p.release();}catch(const std::exception& e){failure(env,e);return 0;}}
extern "C" JNIEXPORT jlong JNICALL JNI_NAME(createQueryRequests)(JNIEnv* env,jclass,jint input,jint output,jint requests,jint owners,jint n,jint q,jint capacity,jfloat radius,jstring cudaRoot,jstring optixRoot,jstring source,jstring crtRoot){try{auto p=std::make_unique<Experiment>();p->initialize(input,output,n,q,capacity,radius,string(env,cudaRoot),string(env,optixRoot),string(env,source),string(env,crtRoot),requests,owners);return (jlong)p.release();}catch(const std::exception& e){failure(env,e);return 0;}}
extern "C" JNIEXPORT jdoubleArray JNICALL JNI_NAME(runQuery)(JNIEnv* env,jclass,jlong handle,jint backend,jboolean refit){try{if(backend<0||backend>4)throw std::runtime_error("Unknown R7 backend");auto* p=(Experiment*)handle;p->run(backend,refit);auto result=env->NewDoubleArray(p->last.size());env->SetDoubleArrayRegion(result,0,p->last.size(),p->last.data());return result;}catch(const std::exception& e){failure(env,e);return nullptr;}}
extern "C" JNIEXPORT void JNICALL JNI_NAME(closeQuery)(JNIEnv*,jclass,jlong handle){delete (Experiment*)handle;}
extern "C" JNIEXPORT jlongArray JNICALL JNI_NAME(queryMemory)(JNIEnv* env,jclass,jlong handle){
    try{auto p=(Experiment*)handle;cuda(cuCtxSetCurrent(p->context));size_t freeBytes,totalBytes;cuda(cuMemGetInfo(&freeBytes,&totalBytes));
        jlong values[]={(jlong)p->scratchSize,(jlong)(p->gasSize+p->triangleGasBytes),(jlong)p->owned,(jlong)freeBytes,(jlong)totalBytes};
        auto result=env->NewLongArray(5);env->SetLongArrayRegion(result,0,5,values);return result;
    }catch(const std::exception& e){failure(env,e);return nullptr;}
}

void* createDetachedQuery(unsigned n,unsigned q,unsigned capacity,const std::string& c,const std::string& o,const std::string& s,const std::string& crt,unsigned mask){auto p=std::make_unique<Experiment>();p->initialize(0,0,n,q,capacity,1.f,c,o,s,crt,0,0,mask);return p.release();}
CUstream detachedQueryStream(void* h){return ((Experiment*)h)->stream;}
void enqueueDetachedQuery(void* h,const QueryView& v,int backend,bool refit,CUexternalSemaphore done,uint64_t value){
    auto* e=(Experiment*)h;if(v.n>e->nCapacity||v.q>e->qCapacity||backend<0||backend>4||!std::isfinite(v.radius)||v.radius<=0)throw std::runtime_error("Invalid detached query dimensions");
    auto& p=e->p;bool changed=p.n!=v.n;e->blocks=(v.q+255)/256;p.n=v.n;p.q=v.q;p.radius=v.radius;p.capacity=v.capacity;p.points=(const float4*)v.points;p.requests=(const float4*)v.requests;p.owners=(const unsigned*)v.owners;p.poolIndices=(const unsigned*)v.poolIndices;p.header=(unsigned long long*)(v.output+64);p.offsets=(unsigned long long*)(v.output+80);p.ids=(unsigned*)(v.output+v.idsOffsetWords*4ull);p.write=0;p.overlapDirections=(const float4*)v.overlapDirections;
    if(p.fused){if(v.stage!=1||v.queryLookup||!v.overlapDirections||v.output==0||v.q*16ull>v.capacity*4ull)throw std::runtime_error("Fused ordinary output capacity/stage invalid");p.forces=(float4*)(v.output+v.idsOffsetWords*4ull);}
    e->dispatchGpu(backend,refit&&!changed);cuda(cuEventRecord(e->extra[0],e->stream));if(v.diagnosticSort&&!p.fused){void* sortArgs[]={&p};e->launch(e->sortRows,(p.q+255)/256,sortArgs);}cuda(cuEventRecord(e->extra[1],e->stream));if(v.queryLookup)cuda(cuMemcpyDtoDAsync(v.output+v.lookupOffsetWords*4ull,v.queryLookup,131072ull*4,e->stream));CUdeviceptr envelope=v.output;unsigned lo=unsigned(v.nonce),hi=unsigned(v.nonce>>32),live=v.live,offset=v.idsOffsetWords,epochLo=unsigned(v.epoch),epochHi=unsigned(v.epoch>>32),stage=v.stage,lookup=v.lookupOffsetWords;void* args[]={&p,&envelope,&lo,&hi,&live,&offset,&epochLo,&epochHi,&stage,&lookup};e->launch(e->seal,1,args);cuda(cuEventRecord(e->extra[2],e->stream));
    CUDA_EXTERNAL_SEMAPHORE_SIGNAL_PARAMS signal{};signal.params.fence.value=value;cuda(cuSignalExternalSemaphoresAsync(&done,&signal,1,e->stream));cuda(cuEventRecord(e->end,e->stream));
}
CUresult detachedQueryReady(void* h){auto* e=(Experiment*)h;return cuEventQuery(e->end);}
std::array<double,7> detachedQueryTiming(void* h){auto* e=(Experiment*)h;float ms{};cuda(cuEventElapsedTime(&ms,e->begin,e->end));e->last[0]=ms;for(int i=0;i<5;i++){cuda(cuEventElapsedTime(&ms,i?e->marks[i-1]:e->begin,e->marks[i]));e->last[i+2]=ms;}return e->last;}
void destroyDetachedQuery(void* h){auto* e=(Experiment*)h;cuda(cuStreamQuery(e->stream));e->externallyRetired=true;delete e;}

void configureDetachedQuery(void* h,unsigned policy,unsigned pageBytes){auto* e=(Experiment*)h;cuda(cuCtxSetCurrent(e->context));e->policy=policy;e->p.fused=(policy&64u)?1u:0u;e->p.boundsPadding=(policy&128u)?.25f:0.f;
if((policy&128u)&&!e->cachedPoints){e->alloc(e->cachedPoints,e->nCapacity*16ull);e->alloc(e->cacheDirty,4);}
e->p.paged=(policy&4u)?1:0;if(e->p.paged&&!e->pages){if(pageBytes<144||pageBytes>(64u<<20))throw std::runtime_error("R8 page pool must be 144B..64MiB");e->alloc(e->pages,pageBytes);e->alloc(e->pageHeads,e->qCapacity*4ull);e->alloc(e->pageTails,e->qCapacity*4ull);e->alloc(e->pageCounter,4);e->p.pages=(unsigned*)e->pages;e->p.pageHeads=(unsigned*)e->pageHeads;e->p.pageTails=(unsigned*)e->pageTails;e->p.pageCounter=(unsigned*)e->pageCounter;e->p.pageCapacity=pageBytes/144;}}

std::array<double,3> detachedQueryDetails(void* h){auto* e=(Experiment*)h;std::array<double,3> result{};float ms{};cuda(cuEventElapsedTime(&ms,e->extra[0],e->extra[1]));result[0]=ms;cuda(cuEventElapsedTime(&ms,e->extra[1],e->extra[2]));result[1]=ms;cuda(cuEventElapsedTime(&ms,e->extra[2],e->end));result[2]=ms;return result;}
