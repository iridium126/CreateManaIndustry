// Uses the same voxel cells as GL. A missing page cannot certify free space.
struct StormBody { bool occupied, complete; };
struct StormCollision {
    StormTerrain terrain;
    int3 cached{};
    int page=-1;
    bool hasPage=false;

    __device__ static bool same(int3 a,int3 b){return a.x==b.x&&a.y==b.y&&a.z==b.z;}
    __device__ static int3 block(vec3 p){return make_int3(__float2int_rd(p.x),__float2int_rd(p.y),__float2int_rd(p.z));}
    __device__ static int3 section(int3 p){return make_int3(p.x>>4,p.y>>4,p.z>>4);}
    __device__ int lookup(int3 p){
        if(!terrain.rows||terrain.mask==0)return -1;
        if(hasPage&&same(cached,p))return page;
        cached=p;hasPage=true;page=-1;
        unsigned h=unsigned(p.x)*0x8da6b343u^unsigned(p.y)*0xd8163841u^unsigned(p.z)*0xcb1ab31fu;
        unsigned row=(h^(h>>16))&terrain.mask;
        for(unsigned i=0;i<8;i++,row=(row+1)&terrain.mask){
            auto v=terrain.rows[row];
            if(v.w==-1)break;
            if(v.x==p.x&&v.y==p.y&&v.z==p.z){page=v.w;break;}
        }
        return page;
    }
    __device__ bool solid(int3 p,int id) const{
        if(id<0)return id==-3;
        unsigned cell=unsigned((p.x&15)|((p.z&15)<<4)|((p.y&15)<<8));
        return (terrain.cells[unsigned(id)*128u+(cell>>5)]&(1u<<(cell&31u)))!=0;
    }
    __device__ StormBody body(vec3 feet){
        auto lo=block(feet+vec3(-.17499f,.00001f,-.17499f));
        auto hi=block(feet+vec3(.17499f,.59999f,.17499f));
        auto key=section(lo);
        bool single=same(key,section(hi));
        int shared=single?lookup(key):-4;
        if(single&&shared<0)return {shared==-3,shared!=-1};
        StormBody result{false,true};
        for(int x=lo.x;x<=hi.x;x++)for(int y=lo.y;y<=hi.y;y++)for(int z=lo.z;z<=hi.z;z++){
            auto p=make_int3(x,y,z);
            int id=single?shared:lookup(section(p));
            result.complete=result.complete&&id!=-1;
            result.occupied=result.occupied||solid(p,id);
        }
        return result;
    }
    __device__ bool airSweep(vec3 start,vec3 end) {
        auto key=section(block(start));
        if(lookup(key)!=-2)return false;
        auto lo=section(block(vec3(fminf(start.x,end.x)-.175f,fminf(start.y,end.y),fminf(start.z,end.z)-.175f)));
        auto hi=section(block(vec3(fmaxf(start.x,end.x)+.175f,fmaxf(start.y,end.y)+.6f,fmaxf(start.z,end.z)+.175f)));
        return same(key,lo)&&same(key,hi);
    }
    __device__ unsigned sweep(vec3& position,vec3& velocity,float dt){
        auto end=position+velocity*dt;
        if(airSweep(position,end)){position=end;return 0;}
        auto before=body(position);
        unsigned result=before.complete?0u:StormCoverageUnknown;
        if(before.occupied){velocity=vec3(0.f);return result|StormTerrainClipped|StormEmbedded;}
        float travel=fmaxf(fabsf(velocity.x),fmaxf(fabsf(velocity.y),fabsf(velocity.z)))*dt;
        int steps=1+int(clamp(travel/.5f,0.f,15.f));
        float step=dt/float(steps);
        for(int i=0;i<steps;i++)for(int axis=0;axis<3;axis++){
            float& v=axis==0?velocity.x:axis==1?velocity.y:velocity.z;
            float move=v*step;
            if(move==0.f)continue;
            auto next=position;
            (axis==0?next.x:axis==1?next.y:next.z)+=move;
            auto probe=body(next);
            if(!probe.complete)result|=StormCoverageUnknown;
            if(probe.occupied){v=0.f;result|=StormTerrainClipped;}
            else position=next;
        }
        return result;
    }
};
