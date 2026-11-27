// Matches the native Storm terrain index and the voxel coordinates in the tick packet.
uint stormMix(uint v) {
    v=(v^(v>>16))*0x7feb352du;
    v=(v^(v>>15))*0x846ca68bu;
    return v^(v>>16);
}
uint stormSectionHash(int x,int y,int z){
    uint a=stormMix(uint(x)+0x9e3779b9u), b=stormMix(uint(y)+0x85ebca6bu), c=stormMix(uint(z)+0xc2b2ae35u);
    return stormMix(a^((b<<11)|(b>>21))^((c<<22)|(c>>10)));
}
bool sameSection(int3 a,int3 b) { return all(a==b); }
int3 voxelBlock(float3 p) { return int3(floor(p)); }
int3 voxelSection(int3 p) { return p>>4; }
int4 stormEditRow(uint row) {
    uint at=4u+row*4u;
    return asint(uint4(terrainEdits[at],terrainEdits[at+1u],terrainEdits[at+2u],terrainEdits[at+3u]));
}
int stormEditPage(int3 key) {
    if(!terrainEdited)return -1;
    uint primary=terrainEdits[0],mask=primary-1u;
    uint row=stormSectionHash(key.x,key.y,key.z)&mask;
    for(uint i=0;i<8;i++,row=(row+1)&mask){
        int4 value=stormEditRow(row);
        if(value.w<0)break;
        if(all(value.xyz==key))return value.w;
    }
    uint lo=primary,hi=lo+terrainEdits[1];
    while(lo<hi){
        uint mid=(lo+hi)/2;int4 v=stormEditRow(mid);
        int c=v.x<key.x?-1:v.x>key.x?1:v.y<key.y?-1:v.y>key.y?1:v.z<key.z?-1:v.z>key.z?1:0;
        if(c<0)lo=mid+1;else if(c>0)hi=mid;else return v.w;
    }
    return -1;
}
// Uses the same voxel cells as GL. A missing page cannot certify free space.
struct StormBody { bool occupied, complete; };
struct StormCollision {
    int3 cached;
    int page;
    bool hasPage;
    bool hitDown;
    float3 impactPoint;




    int lookup(int3 p){
        if(terrainMask==0)return -1;
        if(hasPage&&sameSection(cached,p))return page;
        cached=p;hasPage=true;page=-1;
        uint row=stormSectionHash(p.x,p.y,p.z)&terrainMask;
        for(uint i=0;i<8;i++,row=(row+1)&terrainMask){
            int4 v=terrainRows[row];
            if(v.w==-1)break;
            if(v.x==p.x&&v.y==p.y&&v.z==p.z){page=v.w;break;}
        }
        if(page==-1){
            uint lo=terrainMask+1,hi=lo+terrainOverflow;
            while(lo<hi){uint mid=lo+(hi-lo)/2;int4 v=terrainRows[mid];
                int c=v.x<p.x?-1:v.x>p.x?1:v.y<p.y?-1:v.y>p.y?1:v.z<p.z?-1:v.z>p.z?1:0;
                if(c<0)lo=mid+1;else if(c>0)hi=mid;else{page=v.w;break;}}
        }
        return page;
    }
    bool solid(int3 p,int id){
        if(id<0&&id!=-3)return false;
        uint cell=uint((p.x&15)|((p.z&15)<<4)|((p.y&15)<<8));
        int edit=stormEditPage(voxelSection(p));
        if(edit>=0){
            uint at=4u+4u*(terrainEdits[0]+terrainEdits[1])+uint(edit)*128u+(cell>>5);
            if((terrainEdits[at]&(1u<<(cell&31u)))!=0u)return false;
        }
        if(id==-3)return true;
        return (terrainCells[uint(id)*128u+(cell>>5)]&(1u<<(cell&31u)))!=0;
    }
    StormBody body(float3 feet){
        int3 lo=voxelBlock(feet+float3(-.17499f,.00001f,-.17499f));
        int3 hi=voxelBlock(feet+float3(.17499f,.59999f,.17499f));
        int3 key=voxelSection(lo);
        bool single=sameSection(key,voxelSection(hi));
        int pageSlot=single?lookup(key):-4;
        if(single&&pageSlot<0&&(pageSlot!=-3||stormEditPage(key)<0)){StormBody body={pageSlot==-3,pageSlot!=-1};return body;}
        StormBody result={false,true};
        for(int x=lo.x;x<=hi.x;x++)for(int y=lo.y;y<=hi.y;y++)for(int z=lo.z;z<=hi.z;z++){
            int3 p=int3(x,y,z);
            int id=single?pageSlot:lookup(voxelSection(p));
            result.complete=result.complete&&id!=-1;
            result.occupied=result.occupied||solid(p,id);
        }
        return result;
    }
    bool airSweep(float3 start,float3 end) {
        int3 key=voxelSection(voxelBlock(start));
        if(lookup(key)!=-2)return false;
        int3 lo=voxelSection(voxelBlock(float3(min(start.x,end.x)-.175f,min(start.y,end.y),min(start.z,end.z)-.175f)));
        int3 hi=voxelSection(voxelBlock(float3(max(start.x,end.x)+.175f,max(start.y,end.y)+.6f,max(start.z,end.z)+.175f)));
        return sameSection(key,lo)&&sameSection(key,hi);
    }
    uint sweep(inout float3 position,inout float3 velocity,float dt){
        hitDown=false;
        float3 end=position+velocity*dt;
        if(airSweep(position,end)){position=end;return 0;}
        StormBody before=body(position);
        uint result=before.complete?0u:StormCoverageUnknown;
        if(before.occupied){velocity=(float3)(0.f);return result|StormTerrainClipped|StormEmbedded;}
        float travel=max(abs(velocity.x),max(abs(velocity.y),abs(velocity.z)))*dt;
        int steps=1+int(clamp(travel/.5f,0.f,15.f));
        float step=dt/float(steps);
        for(int i=0;i<steps;i++)for(int axis=0;axis<3;axis++){

            float move=velocity[axis]*step;
            if(move==0.f)continue;
            float3 next=position;
            next[axis]+=move;
            StormBody probe=body(next);
            if(!probe.complete)result|=StormCoverageUnknown;
            if(probe.occupied){
                if(axis==1&&move<0.f){hitDown=true;impactPoint=position;}
                velocity[axis]=0.f;result|=StormTerrainClipped;
            }
            else position=next;
        }
        return result;
    }
};
