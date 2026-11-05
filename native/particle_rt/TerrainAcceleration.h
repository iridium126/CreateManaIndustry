#pragma once
#include "Spatial.h"
#include "Common.h"
#include <optix_stubs.h>
#include <map>
#include <tuple>
#include <vector>
#include <memory>
#include <cstring>
#include <algorithm>

struct TerrainSnapshot {
    uint64_t scene{},revision{};
    std::vector<SpatialParticle> rows;
};

// A section owns real triangles. Moving member proxies never enter the opaque mask.
// The worker stream orders updates after preceding queries; removed meshes retire
// behind an event rather than synchronizing the device or invalidating live jobs.
class TerrainAcceleration {
    struct Memory {
        CUdeviceptr device{};size_t size{};
        Memory()=default;Memory(const Memory&)=delete;Memory& operator=(const Memory&)=delete;
        ~Memory(){reset();}
        void reset(){if(device){cuMemFree(device);particleRtOwnedBytes-=size;device=0;size=0;}}
        void allocate(size_t bytes){reset();size=std::max<size_t>(16,bytes);reserveBytes(size);try{checked(cuMemAlloc(&device,size));}catch(...){particleRtOwnedBytes-=size;size=0;throw;}}
    };
    struct Mesh {
        Memory vertices,indices,gas,scratch;
        OptixTraversableHandle handle{};
        void* upload{};
        std::vector<SpatialParticle> content;
        void finishBuild(){if(upload){cuMemFreeHost(upload);upload=nullptr;}scratch.reset();}
        ~Mesh(){if(upload)cuMemFreeHost(upload);}
    };
    using Key=std::tuple<int,int,int,unsigned>;
    struct Retirement {CUevent ready{};std::shared_ptr<Mesh> mesh;};
    struct BuildBatch {CUevent ready{};std::vector<std::shared_ptr<Mesh>> meshes;};
    std::map<Key,std::shared_ptr<Mesh>> meshes;
    std::vector<Retirement> retired;
    std::vector<BuildBatch> builds;
    Memory views,templates,rowMap,boxRecords;
    SpatialTerrainView* hostViews[3]{};
    OptixInstance* hostInstances[3]{};
    unsigned* hostRows[3]{};
    SpatialParticle* hostBoxes[3]{};
    unsigned capacity;
    uint64_t scene{},revision{};
    unsigned active{};
    bool published{};
    static void optixCheck(OptixResult result){if(result!=OPTIX_SUCCESS)throw std::runtime_error(optixGetErrorName(result));}
    static bool equal(const std::vector<SpatialParticle>& a,const std::vector<SpatialParticle>& b){
        if(a.size()!=b.size())return false;
        for(size_t i=0;i<a.size();i++){
            if(std::memcmp(&a[i].position,&b[i].position,16)||std::memcmp(&a[i].velocity,&b[i].velocity,12))return false;
        }
        return true;
    }
    std::shared_ptr<Mesh> build(OptixDeviceContext context,CUstream stream,std::vector<SpatialParticle> rows){
        auto mesh=std::make_shared<Mesh>();mesh->content=std::move(rows);
        size_t boxes=mesh->content.size(),vb=boxes*8*12,ib=boxes*12*12;
        checked(cuMemHostAlloc(&mesh->upload,vb+ib,0));
        auto vertices=static_cast<float*>(mesh->upload);auto triangles=reinterpret_cast<unsigned*>(static_cast<char*>(mesh->upload)+vb);
        const unsigned faces[]={0,2,1,0,3,2,4,5,6,4,6,7,0,4,7,0,7,3,1,2,6,1,6,5,0,1,5,0,5,4,3,7,6,3,6,2};
        const int corner[8][3]={{-1,-1,-1},{1,-1,-1},{1,1,-1},{-1,1,-1},{-1,-1,1},{1,-1,1},{1,1,1},{-1,1,1}};
        for(size_t b=0;b<boxes;b++){
            const auto& row=mesh->content[b];float center[]={row.position.x,row.position.y,row.position.z},half[]={row.velocity.x,row.velocity.y,row.velocity.z};
            for(unsigned v=0;v<8;v++)for(unsigned axis=0;axis<3;axis++)vertices[b*24+v*3+axis]=center[axis]+corner[v][axis]*half[axis];
            for(unsigned f=0;f<36;f++)triangles[b*36+f]=unsigned(b*8)+faces[f];
        }
        mesh->vertices.allocate(vb);mesh->indices.allocate(ib);
        checked(cuMemcpyHtoDAsync(mesh->vertices.device,vertices,vb,stream));checked(cuMemcpyHtoDAsync(mesh->indices.device,triangles,ib,stream));
        unsigned flags=OPTIX_GEOMETRY_FLAG_DISABLE_ANYHIT;OptixBuildInput input{};input.type=OPTIX_BUILD_INPUT_TYPE_TRIANGLES;
        input.triangleArray.vertexBuffers=&mesh->vertices.device;input.triangleArray.numVertices=unsigned(boxes*8);input.triangleArray.vertexFormat=OPTIX_VERTEX_FORMAT_FLOAT3;input.triangleArray.vertexStrideInBytes=12;
        input.triangleArray.indexBuffer=mesh->indices.device;input.triangleArray.numIndexTriplets=unsigned(boxes*12);input.triangleArray.indexFormat=OPTIX_INDICES_FORMAT_UNSIGNED_INT3;input.triangleArray.indexStrideInBytes=12;input.triangleArray.flags=&flags;input.triangleArray.numSbtRecords=1;
        OptixAccelBuildOptions options{};options.buildFlags=OPTIX_BUILD_FLAG_PREFER_FAST_TRACE;options.operation=OPTIX_BUILD_OPERATION_BUILD;
        OptixAccelBufferSizes size{};optixCheck(optixAccelComputeMemoryUsage(context,&options,&input,1,&size));mesh->gas.allocate(size.outputSizeInBytes);mesh->scratch.allocate(size.tempSizeInBytes);
        optixCheck(optixAccelBuild(context,stream,&options,&input,1,mesh->scratch.device,mesh->scratch.size,mesh->gas.device,mesh->gas.size,&mesh->handle,nullptr,0));return mesh;
    }
    void retire(CUstream stream,std::shared_ptr<Mesh> mesh){CUevent ready{};checked(cuEventCreate(&ready,CU_EVENT_DISABLE_TIMING));checked(cuEventRecord(ready,stream));retired.push_back({ready,std::move(mesh)});}
public:
    explicit TerrainAcceleration(unsigned maxRows):capacity(std::max(1u,maxRows*2)){
        views.allocate(size_t(capacity)*sizeof(SpatialTerrainView));
        templates.allocate(size_t(capacity)*sizeof(OptixInstance));
        rowMap.allocate(size_t(capacity)*sizeof(unsigned));
        boxRecords.allocate(size_t(maxRows)*sizeof(SpatialParticle));
        try{for(unsigned i=0;i<3;i++){
            checked(cuMemHostAlloc(reinterpret_cast<void**>(&hostViews[i]),size_t(capacity)*sizeof(SpatialTerrainView),0));
            checked(cuMemHostAlloc(reinterpret_cast<void**>(&hostInstances[i]),size_t(capacity)*sizeof(OptixInstance),0));
            checked(cuMemHostAlloc(reinterpret_cast<void**>(&hostRows[i]),size_t(capacity)*sizeof(unsigned),0));
            checked(cuMemHostAlloc(reinterpret_cast<void**>(&hostBoxes[i]),std::max<size_t>(1,maxRows)*sizeof(SpatialParticle),0));
        }}catch(...){freeHost();throw;}
    }
    ~TerrainAcceleration(){for(auto& r:retired)cuEventDestroy(r.ready);for(auto& b:builds)cuEventDestroy(b.ready);freeHost();}
    void freeHost(){
        for(unsigned i=0;i<3;i++){
            if(hostViews[i])cuMemFreeHost(hostViews[i]);
            if(hostInstances[i])cuMemFreeHost(hostInstances[i]);
            if(hostRows[i])cuMemFreeHost(hostRows[i]);
            if(hostBoxes[i])cuMemFreeHost(hostBoxes[i]);
        }
    }
    unsigned update(OptixDeviceContext context,CUstream stream,unsigned slot,const TerrainSnapshot& snapshot,unsigned memberCapacity,unsigned members,CUdeviceptr instances){
        for(auto it=retired.begin();it!=retired.end();){auto result=cuEventQuery(it->ready);if(result==CUDA_ERROR_NOT_READY){++it;continue;}checked(result);cuEventDestroy(it->ready);it=retired.erase(it);}
        for(auto it=builds.begin();it!=builds.end();){
            auto result=cuEventQuery(it->ready);if(result==CUDA_ERROR_NOT_READY){++it;continue;}checked(result);
            for(auto& mesh:it->meshes)mesh->finishBuild();cuEventDestroy(it->ready);it=builds.erase(it);
        }
        if(published&&scene==snapshot.scene&&revision==snapshot.revision){
            if(active)checked(cuMemcpyDtoDAsync(instances+members*sizeof(OptixInstance),templates.device,active*sizeof(OptixInstance),stream));
            return active;
        }
        if(scene!=snapshot.scene){for(auto& entry:meshes)retire(stream,std::move(entry.second));meshes.clear();scene=snapshot.scene;}
        struct Content {std::vector<SpatialParticle> rows;std::vector<unsigned> indices;};std::map<Key,Content> next;
        for(unsigned i=0;i<snapshot.rows.size();i++){
            const auto& row=snapshot.rows[i];
            for(unsigned mask=1;mask<=2;mask++)if(row.identity.w&mask){auto& content=next[{int(row.identity.x),int(row.identity.y),int(row.identity.z),mask}];content.rows.push_back(row);content.indices.push_back(memberCapacity+i);}
        }
        for(auto it=meshes.begin();it!=meshes.end();)if(!next.count(it->first)){retire(stream,std::move(it->second));it=meshes.erase(it);}else ++it;
        unsigned count=0,rowCount=0;std::vector<std::shared_ptr<Mesh>> started;
        for(auto& entry:next){
            auto old=meshes.find(entry.first);
            if(old==meshes.end()||!equal(old->second->content,entry.second.rows)){
                auto mesh=build(context,stream,std::move(entry.second.rows));
                if(old!=meshes.end())retire(stream,std::move(old->second));meshes[entry.first]=std::move(mesh);
                started.push_back(meshes.at(entry.first));
            }
            auto& mesh=*meshes.at(entry.first);auto& instance=hostInstances[slot][count];instance={};instance.transform[0]=instance.transform[5]=instance.transform[10]=1;
            instance.instanceId=memberCapacity+count;instance.visibilityMask=std::get<3>(entry.first);instance.flags=OPTIX_INSTANCE_FLAG_DISABLE_ANYHIT;instance.traversableHandle=mesh.handle;
            // The packet row number is publication state. Shifting rows in
            // another section must never invalidate this section's GAS.
            auto& indices=entry.second.indices;
            std::copy(indices.begin(),indices.end(),hostRows[slot]+rowCount);
            hostViews[slot][count]={reinterpret_cast<const unsigned*>(rowMap.device)+rowCount,unsigned(indices.size()),0};
            rowCount+=unsigned(indices.size());count++;
        }
        if(!started.empty()){
            CUevent done{};checked(cuEventCreate(&done,CU_EVENT_DISABLE_TIMING));
            auto result=cuEventRecord(done,stream);
            if(result!=CUDA_SUCCESS){cuEventDestroy(done);checked(result);}
            builds.push_back({done,std::move(started)});
        }
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
