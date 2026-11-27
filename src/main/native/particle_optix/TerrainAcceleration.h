#pragma once
#include "Spatial.h"
#include "CudaRuntime.h"
#include <optix_stubs.h>
#include <algorithm>
#include <cstring>
#include <iterator>
#include <list>
#include <map>
#include <memory>
#include <tuple>
#include <vector>

struct TerrainSnapshot {
    uint64_t scene{},revision{};
    std::vector<SpatialParticle> rows;
};

// A section owns a GAS of real triangles. The worker stream orders previous
// traces, reuse of retired GAS ranges, and the next IAS build.
class TerrainAcceleration {
    static constexpr size_t alignment=OPTIX_ACCEL_BUFFER_BYTE_ALIGNMENT;
    static constexpr size_t slabBytes=512*1024;

    static size_t aligned(size_t bytes){return (bytes+alignment-1)&~(alignment-1);}
    static size_t roundedStorage(size_t bytes){return (bytes+slabBytes-1)/slabBytes*slabBytes;}

    struct Memory {
        CUdeviceptr device{};
        size_t size{};
        explicit Memory(size_t bytes):size(std::max<size_t>(16,bytes)){
            reserveBytes(size);
            try{checked(cuMemAlloc(&device,size));}
            catch(...){particleOptixOwnedBytes-=size;throw;}
        }
        Memory(const Memory&)=delete;
        Memory& operator=(const Memory&)=delete;
        ~Memory(){cuMemFree(device);particleOptixOwnedBytes-=size;}
    };
    struct Slab {
        std::unique_ptr<Memory> storage;
        std::map<size_t,size_t> freeRanges;
        size_t used{};

        explicit Slab(size_t bytes):storage(std::make_unique<Memory>(bytes)){freeRanges.emplace(0,bytes);}

        bool take(size_t bytes,size_t& offset){
            if(bytes>storage->size-used)return false;
            auto range=std::find_if(freeRanges.begin(),freeRanges.end(),
                    [bytes](const auto& entry){return entry.second>=bytes;});
            if(range==freeRanges.end())return false;
            offset=range->first;
            if(range->second>bytes)freeRanges.emplace(offset+bytes,range->second-bytes);
            freeRanges.erase(range);
            used+=bytes;
            return true;
        }

        void release(size_t offset,size_t bytes){
            auto next=freeRanges.lower_bound(offset);
            auto previous=next==freeRanges.begin()?freeRanges.end():std::prev(next);
            const bool joinsPrevious=previous!=freeRanges.end()&&previous->first+previous->second==offset;
            const bool joinsNext=next!=freeRanges.end()&&offset+bytes==next->first;
            if(joinsPrevious){
                previous->second+=bytes;
                if(joinsNext){previous->second+=next->second;freeRanges.erase(next);}
            }else{
                freeRanges.emplace_hint(next,offset,bytes+(joinsNext?next->second:0));
                if(joinsNext)freeRanges.erase(next);
            }
            used-=bytes;
        }
    };
    struct GasRange {
        Slab* slab{};
        size_t offset{},bytes{};
        CUdeviceptr device()const{return slab->storage->device+offset;}
        void release(){slab->release(offset,bytes);}
    };
    struct Mesh {
        GasRange gas;
        OptixTraversableHandle handle{};
        std::vector<SpatialParticle> content;
    };
    struct Build {
        OptixAccelBufferSizes sizes;
        size_t vertices,indices,scratchOffset,uploadOffset;
    };
    struct Upload {
        void* data{};
        size_t bytes{};
        ~Upload(){if(data)cuMemFreeHost(data);}
        void ensure(size_t required){
            if(bytes>=required)return;
            const size_t next=roundedStorage(required);
            void* replacement=nullptr;
            checked(cuMemHostAlloc(&replacement,next,0));
            if(data)cuMemFreeHost(data);
            data=replacement;bytes=next;
        }
    };
    using Key=std::tuple<int,int,int,unsigned>;
    struct Retirement {CUevent ready{};std::vector<std::unique_ptr<Memory>> buffers;};
    std::map<Key,Mesh> meshes;
    std::list<Slab> slabs;
    std::list<Retirement> retired;
    std::map<unsigned,OptixAccelBufferSizes> sizesByBoxCount;
    std::unique_ptr<Memory> workspace;
    Upload uploads[3];
    Memory views,templates,rowMap,boxRecords;
    SpatialTerrainView* hostViews[3]{};
    OptixInstance* hostInstances[3]{};
    unsigned* hostRows[3]{};
    SpatialParticle* hostBoxes[3]{};
    const unsigned capacity;
    uint64_t scene{},revision{};
    unsigned active{};
    bool published{};

    static void optixCheck(OptixResult result){
        if(result!=OPTIX_SUCCESS)throw std::runtime_error(optixGetErrorName(result));
    }
    static bool equal(const std::vector<SpatialParticle>& a,const std::vector<SpatialParticle>& b){
        if(a.size()!=b.size())return false;
        for(size_t i=0;i<a.size();i++){
            if(std::memcmp(&a[i].position,&b[i].position,16)||std::memcmp(&a[i].velocity,&b[i].velocity,12))return false;
        }
        return true;
    }
    static OptixBuildInput triangles(unsigned boxes,const CUdeviceptr* vertices,CUdeviceptr indices,const unsigned* flags){
        OptixBuildInput input{};
        input.type=OPTIX_BUILD_INPUT_TYPE_TRIANGLES;
        input.triangleArray.vertexBuffers=vertices;input.triangleArray.numVertices=boxes*8;
        input.triangleArray.vertexFormat=OPTIX_VERTEX_FORMAT_FLOAT3;input.triangleArray.vertexStrideInBytes=12;
        input.triangleArray.indexBuffer=indices;input.triangleArray.numIndexTriplets=boxes*12;
        input.triangleArray.indexFormat=OPTIX_INDICES_FORMAT_UNSIGNED_INT3;input.triangleArray.indexStrideInBytes=12;
        input.triangleArray.flags=flags;input.triangleArray.numSbtRecords=1;
        return input;
    }
    static OptixAccelBuildOptions options(){
        OptixAccelBuildOptions result{};
        result.buildFlags=OPTIX_BUILD_FLAG_PREFER_FAST_TRACE;
        result.operation=OPTIX_BUILD_OPERATION_BUILD;
        return result;
    }
    Build describe(OptixDeviceContext context,unsigned boxes,size_t uploadOffset){
        auto found=sizesByBoxCount.find(boxes);
        if(found==sizesByBoxCount.end()){
            const CUdeviceptr vertices=0;
            const unsigned flags=OPTIX_GEOMETRY_FLAG_DISABLE_ANYHIT;
            auto input=triangles(boxes,&vertices,0,&flags);
            auto buildOptions=options();
            OptixAccelBufferSizes sizes{};
            optixCheck(optixAccelComputeMemoryUsage(context,&buildOptions,&input,1,&sizes));
            found=sizesByBoxCount.emplace(boxes,sizes).first;
        }
        const size_t vertices=size_t(boxes)*8*12,indices=size_t(boxes)*12*12;
        return {found->second,vertices,indices,aligned(vertices+indices),uploadOffset};
    }
    GasRange allocateGas(size_t required){
        const size_t bytes=aligned(required);
        size_t offset;
        for(auto& slab:slabs)if(slab.take(bytes,offset))return {&slab,offset,bytes};
        auto& slab=slabs.emplace_back(std::max(slabBytes,bytes));
        if(!slab.take(bytes,offset))throw std::logic_error("New terrain GAS slab cannot fit its first range");
        return {&slab,offset,bytes};
    }
    Retirement& retirement(CUstream stream,size_t count){
        auto& batch=retired.emplace_back();
        batch.buffers.reserve(count);
        checked(cuEventCreate(&batch.ready,CU_EVENT_DISABLE_TIMING));
        checked(cuEventRecord(batch.ready,stream));
        return batch;
    }
    void collectRetired(){
        while(!retired.empty()){
            auto& batch=retired.front();
            auto result=cuEventQuery(batch.ready);
            if(result==CUDA_ERROR_NOT_READY)return;
            checked(result);cuEventDestroy(batch.ready);retired.pop_front();
        }
    }
    void trimSlabs(CUstream stream,bool keepWarm){
        const size_t empty=std::count_if(slabs.begin(),slabs.end(),[](const Slab& slab){return slab.used==0;});
        const size_t keep=keepWarm?1:0;
        if(empty<=keep)return;
        auto& batch=retirement(stream,empty-keep);
        size_t remaining=empty-keep;
        for(auto it=slabs.end();remaining>0;){
            --it;
            if(it->used>0)continue;
            batch.buffers.push_back(std::move(it->storage));
            it=slabs.erase(it);--remaining;
        }
    }
    void ensureWorkspace(CUstream stream,size_t bytes){
        if(!bytes||(workspace&&workspace->size>=bytes))return;
        auto next=std::make_unique<Memory>(roundedStorage(bytes));
        if(workspace)retirement(stream,1).buffers.push_back(std::move(workspace));
        workspace=std::move(next);
    }
    void build(OptixDeviceContext context,CUstream stream,Mesh& mesh,const Build& work,void* upload){
        auto staging=static_cast<char*>(upload)+work.uploadOffset;
        auto vertices=reinterpret_cast<float*>(staging);
        auto indices=reinterpret_cast<unsigned*>(staging+work.vertices);
        const unsigned faces[]={0,2,1,0,3,2,4,5,6,4,6,7,0,4,7,0,7,3,1,2,6,1,6,5,0,1,5,0,5,4,3,7,6,3,6,2};
        const int corner[8][3]={{-1,-1,-1},{1,-1,-1},{1,1,-1},{-1,1,-1},{-1,-1,1},{1,-1,1},{1,1,1},{-1,1,1}};
        for(size_t b=0;b<mesh.content.size();b++){
            const auto& row=mesh.content[b];
            float center[]={row.position.x,row.position.y,row.position.z},half[]={row.velocity.x,row.velocity.y,row.velocity.z};
            for(unsigned v=0;v<8;v++)for(unsigned axis=0;axis<3;axis++)
                vertices[b*24+v*3+axis]=center[axis]+corner[v][axis]*half[axis];
            for(unsigned f=0;f<36;f++)indices[b*36+f]=unsigned(b*8)+faces[f];
        }
        const CUdeviceptr inputVertices=workspace->device;
        const unsigned flags=OPTIX_GEOMETRY_FLAG_DISABLE_ANYHIT;
        auto input=triangles(unsigned(mesh.content.size()),&inputVertices,inputVertices+work.vertices,&flags);
        auto buildOptions=options();
        // OptiX consumes inputs and scratch during the build. The next copy
        // and build can reuse them because both are queued on the same stream.
        checked(cuMemcpyHtoDAsync(inputVertices,staging,work.vertices+work.indices,stream));
        optixCheck(optixAccelBuild(context,stream,&buildOptions,&input,1,inputVertices+work.scratchOffset,
                work.sizes.tempSizeInBytes,mesh.gas.device(),work.sizes.outputSizeInBytes,&mesh.handle,nullptr,0));
    }
public:
    explicit TerrainAcceleration(unsigned maxRows):
        views(size_t(std::max(1u,maxRows*2))*sizeof(SpatialTerrainView)),
        templates(size_t(std::max(1u,maxRows*2))*sizeof(OptixInstance)),
        rowMap(size_t(std::max(1u,maxRows*2))*sizeof(unsigned)),
        boxRecords(size_t(maxRows)*sizeof(SpatialParticle)),capacity(std::max(1u,maxRows*2)){
        try{for(unsigned i=0;i<3;i++){
            checked(cuMemHostAlloc(reinterpret_cast<void**>(&hostViews[i]),size_t(capacity)*sizeof(SpatialTerrainView),0));
            checked(cuMemHostAlloc(reinterpret_cast<void**>(&hostInstances[i]),size_t(capacity)*sizeof(OptixInstance),0));
            checked(cuMemHostAlloc(reinterpret_cast<void**>(&hostRows[i]),size_t(capacity)*sizeof(unsigned),0));
            checked(cuMemHostAlloc(reinterpret_cast<void**>(&hostBoxes[i]),std::max<size_t>(1,maxRows)*sizeof(SpatialParticle),0));
        }}catch(...){freeHost();throw;}
    }
    ~TerrainAcceleration(){for(auto& batch:retired)if(batch.ready)cuEventDestroy(batch.ready);freeHost();}
    void freeHost(){
        for(unsigned i=0;i<3;i++){
            if(hostViews[i])cuMemFreeHost(hostViews[i]);
            if(hostInstances[i])cuMemFreeHost(hostInstances[i]);
            if(hostRows[i])cuMemFreeHost(hostRows[i]);
            if(hostBoxes[i])cuMemFreeHost(hostBoxes[i]);
        }
    }
    unsigned update(OptixDeviceContext context,CUstream stream,unsigned slot,const TerrainSnapshot& snapshot,
                    unsigned memberCapacity,unsigned members,CUdeviceptr instances,bool copyInstances=true){
        collectRetired();
        if(published&&scene==snapshot.scene&&revision==snapshot.revision){
            if(active&&copyInstances)checked(cuMemcpyDtoDAsync(instances+members*sizeof(OptixInstance),templates.device,active*sizeof(OptixInstance),stream));
            return active;
        }
        if(scene!=snapshot.scene){
            for(auto& entry:meshes)entry.second.gas.release();
            meshes.clear();scene=snapshot.scene;
        }

        struct Content {std::vector<SpatialParticle> rows;std::vector<unsigned> indices;Build work{};};
        std::map<Key,Content> next;
        for(unsigned i=0;i<snapshot.rows.size();i++){
            const auto& row=snapshot.rows[i];
            for(unsigned mask=1;mask<=2;mask++)if(row.identity.w&mask){
                auto& content=next[{int(row.identity.x),int(row.identity.y),int(row.identity.z),mask}];
                content.rows.push_back(row);content.indices.push_back(memberCapacity+i);
            }
        }
        for(auto it=meshes.begin();it!=meshes.end();){
            if(!next.count(it->first)){it->second.gas.release();it=meshes.erase(it);}
            else ++it;
        }
        size_t uploadBytes=0,workspaceBytes=0;
        for(auto& [key,content]:next){
            auto old=meshes.find(key);
            if(old!=meshes.end()&&equal(old->second.content,content.rows))continue;
            if(old!=meshes.end()){old->second.gas.release();meshes.erase(old);}
            content.work=describe(context,unsigned(content.rows.size()),uploadBytes);
            uploadBytes+=content.work.vertices+content.work.indices;
            workspaceBytes=std::max(workspaceBytes,content.work.scratchOffset+content.work.sizes.tempSizeInBytes);
        }
        // Host staging is exclusive to a completion-gated slot until its
        // asynchronous copies have completed. Device work is stream ordered.
        uploads[slot].ensure(uploadBytes);
        ensureWorkspace(stream,workspaceBytes);

        unsigned count=0,rowCount=0;
        for(auto& [key,content]:next){
            auto found=meshes.find(key);
            if(content.work.vertices){
                found=meshes.try_emplace(key).first;
                auto& mesh=found->second;
                mesh.content=std::move(content.rows);
                mesh.gas=allocateGas(content.work.sizes.outputSizeInBytes);
                build(context,stream,mesh,content.work,uploads[slot].data);
            }
            const auto& mesh=found->second;
            auto& instance=hostInstances[slot][count];instance={};instance.transform[0]=instance.transform[5]=instance.transform[10]=1;
            instance.instanceId=memberCapacity+count;instance.visibilityMask=std::get<3>(key);instance.flags=OPTIX_INSTANCE_FLAG_DISABLE_ANYHIT;instance.traversableHandle=mesh.handle;
            // Packet row numbers can shift without changing a section's GAS.
            auto& indices=content.indices;
            std::copy(indices.begin(),indices.end(),hostRows[slot]+rowCount);
            hostViews[slot][count]={reinterpret_cast<const unsigned*>(rowMap.device)+rowCount,unsigned(indices.size()),0};
            rowCount+=unsigned(indices.size());count++;
        }
        trimSlabs(stream,count!=0);
        if(count){
            std::copy(snapshot.rows.begin(),snapshot.rows.end(),hostBoxes[slot]);
            checked(cuMemcpyHtoDAsync(boxRecords.device,hostBoxes[slot],snapshot.rows.size()*sizeof(SpatialParticle),stream));
            checked(cuMemcpyHtoDAsync(rowMap.device,hostRows[slot],rowCount*sizeof(unsigned),stream));
            checked(cuMemcpyHtoDAsync(views.device,hostViews[slot],count*sizeof(SpatialTerrainView),stream));
            checked(cuMemcpyHtoDAsync(templates.device,hostInstances[slot],count*sizeof(OptixInstance),stream));
            checked(cuMemcpyDtoDAsync(instances+members*sizeof(OptixInstance),templates.device,count*sizeof(OptixInstance),stream));
        }
        revision=snapshot.revision;active=count;published=true;
        return count;
    }
    const SpatialTerrainView* deviceViews()const{return reinterpret_cast<const SpatialTerrainView*>(views.device);}
    const SpatialParticle* deviceRows()const{return reinterpret_cast<const SpatialParticle*>(boxRecords.device);}
};
