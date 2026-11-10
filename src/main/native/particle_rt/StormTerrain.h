#pragma once
#include "StormStep.h"
#include "Spatial.h"
#include <map>
#include <tuple>
#include <vector>
#include <array>
#include <cstring>
#include <stdexcept>

#ifdef __CUDACC__
#define CMI_TERRAIN_FN __host__ __device__
#else
#define CMI_TERRAIN_FN
#endif
CMI_TERRAIN_FN inline uint32_t stormMix(uint32_t v){v=(v^(v>>16))*0x7feb352du;v=(v^(v>>15))*0x846ca68bu;return v^(v>>16);}
CMI_TERRAIN_FN inline uint32_t stormSectionHash(int x,int y,int z){
    auto a=stormMix(uint32_t(x)+0x9e3779b9u),b=stormMix(uint32_t(y)+0x85ebca6bu),c=stormMix(uint32_t(z)+0xc2b2ae35u);
    return stormMix(a^((b<<11)|(b>>21))^((c<<22)|(c>>10)));
}
#undef CMI_TERRAIN_FN

#ifndef __CUDACC__
/** Worker-owned incremental occupancy. GPU readers see one stream-ordered revision. */
class StormTerrainCache {
    using Key=std::tuple<int,int,int>;
    struct Page {int slot=-2;std::vector<SpatialParticle> boxes;};
    std::map<Key,Page> pages;
    std::vector<unsigned> freeSlots;
    unsigned nextSlot{};
    uint64_t revision{};
    bool initialized{};
    static std::vector<SpatialParticle> geometry(const int4& row,const uint32_t* words){
        std::vector<SpatialParticle> boxes;
        if(row.w==-2)return boxes;
        if(row.w==-3){
            SpatialParticle box{};box.position={float(row.x)*16+8,float(row.y)*16+8,float(row.z)*16+8,0};
            box.velocity={8,8,8,0};box.identity={unsigned(row.x),unsigned(row.y),unsigned(row.z),1};
            boxes.push_back(box);return boxes;
        }
        std::array<unsigned char,4096> cells{};
        for(unsigned i=0;i<4096;i++)cells[i]=row.w==-3||((words[i>>5]>>(i&31))&1u);
        for(unsigned y=0;y<16;y++)for(unsigned z=0;z<16;z++)for(unsigned x=0;x<16;x++){
            unsigned i=x+(z<<4)+(y<<8);if(!cells[i])continue;
            unsigned dx=1,dz=1,dy=1;
            while(x+dx<16&&cells[i+dx])dx++;
            for(;z+dz<16;dz++){bool full=true;for(unsigned a=0;a<dx;a++)full&=cells[i+(dz<<4)+a]!=0;if(!full)break;}
            for(;y+dy<16;dy++){bool full=true;for(unsigned b=0;b<dz;b++)for(unsigned a=0;a<dx;a++)full&=cells[i+(dy<<8)+(b<<4)+a]!=0;if(!full)break;}
            for(unsigned b=0;b<dy;b++)for(unsigned c=0;c<dz;c++)for(unsigned a=0;a<dx;a++)cells[i+(b<<8)+(c<<4)+a]=0;
            if(boxes.size()>=256)return boxes; // Same bounded steering proxy as SpatialTerrain; occupancy remains complete.
            SpatialParticle box{};
            box.position={float(row.x)*16+x+dx*.5f,float(row.y)*16+y+dy*.5f,float(row.z)*16+z+dz*.5f,0};
            box.velocity={dx*.5f,dy*.5f,dz*.5f,0};box.identity={unsigned(row.x),unsigned(row.y),unsigned(row.z),1};
            boxes.push_back(box);
        }
        return boxes;
    }
public:
    std::vector<int4> index;
    StormTerrainCache(){index.reserve(CMI_RESOURCE_STORM_TERRAIN_MAX_ROWS+CMI_RESOURCE_STORM_TERRAIN_MAX_SECTIONS);}
    std::vector<std::pair<unsigned,unsigned>> uploads; // atlas slot, payload page
    unsigned primary=16,overflow{};
    bool apply(const StormTick& tick,const unsigned char* input){
        bool reset=tick.terrainBaseRevision==UINT64_MAX;
        bool changed=reset||tick.terrainRevision!=0||tick.terrainRows;
        if(!changed)return false;
        if(!reset&&(!initialized||tick.terrainBaseRevision!=revision||tick.terrainRevision<=revision))
            throw std::runtime_error("Storm terrain delta revision mismatch");
        if(!reset&&tick.terrainRows==0){revision=tick.terrainRevision;return false;}
        if(reset){pages.clear();freeSlots.clear();nextSlot=0;}
        uploads.clear();
        // Withdraw all removed/replaced mixed pages first: a full-capacity delta may reuse their slots.
        for(unsigned i=0;i<tick.terrainRows;i++){
            int4 row;std::memcpy(&row,input+i*sizeof(row),sizeof(row));
            auto found=pages.find({row.x,row.y,row.z});
            if(found!=pages.end()){if(found->second.slot>=0)freeSlots.push_back(unsigned(found->second.slot));pages.erase(found);}
        }
        for(unsigned i=0;i<tick.terrainRows;i++){
            int4 row;std::memcpy(&row,input+i*sizeof(row),sizeof(row));if(row.w==-1)continue;
            Key key{row.x,row.y,row.z};
            if(pages.count(key))throw std::runtime_error("Duplicate Storm terrain delta section");
            int slot=row.w;const uint32_t* words=nullptr;
            if(slot>=0){
                if(freeSlots.empty())slot=int(nextSlot++);else{slot=int(freeSlots.back());freeSlots.pop_back();}
                if(unsigned(slot)>=CMI_RESOURCE_STORM_TERRAIN_MAX_PAGES)throw std::runtime_error("Storm collision mixed pages exceed limit=16384");
                uploads.emplace_back(unsigned(slot),unsigned(row.w));
                words=reinterpret_cast<const uint32_t*>(input+size_t(tick.terrainRows)*16+size_t(row.w)*512);
            }
            pages.emplace(key,Page{slot,geometry(row,words)});
        }
        if(pages.size()>CMI_RESOURCE_STORM_TERRAIN_MAX_SECTIONS)
            throw std::runtime_error("Storm collision sections="+std::to_string(pages.size())+" exceed limit=32768");
        primary=16;while(primary<pages.size()*2)primary*=2;
        index.assign(primary,int4{0,0,0,-1});
        // Ordered map iteration also produces the exact signed lexicographic overflow order.
        for(const auto& entry:pages){
            auto [x,y,z]=entry.first;unsigned row=stormSectionHash(x,y,z)&(primary-1),probe=0;
            while(probe<8&&index[row].w!=-1){row=(row+1)&(primary-1);probe++;}
            int4 value{x,y,z,entry.second.slot};if(probe==8)index.push_back(value);else index[row]=value;
        }
        overflow=unsigned(index.size())-primary;revision=tick.terrainRevision;initialized=true;return true;
    }
    std::vector<SpatialParticle> geometryRows()const{
        std::vector<SpatialParticle> result;
        for(const auto& entry:pages)for(const auto& box:entry.second.boxes){
            if(result.size()==CMI_RESOURCE_STORM_SPATIAL_TERRAIN_LIMIT)return result;
            result.push_back(box);
        }
        return result;
    }
};
#endif
