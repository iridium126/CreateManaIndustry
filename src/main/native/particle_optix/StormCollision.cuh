// Uses the same voxel cells as GL. A missing page cannot certify free space.
struct StormBody { bool occupied, complete; };
struct StormCollision {
    StormTerrain terrain;
    int3 cached{};
    int page=-1;
    bool hasPage=false;
    bool hitDown=false;
    vec3 impactPoint{0.f};

    __device__ static bool same(int3 a,int3 b){return a.x==b.x&&a.y==b.y&&a.z==b.z;}
    __device__ static int3 block(vec3 p){return make_int3(__float2int_rd(p.x),__float2int_rd(p.y),__float2int_rd(p.z));}
    __device__ static int3 section(int3 p){return make_int3(p.x>>4,p.y>>4,p.z>>4);}
    __device__ int editPage(int3 key) const{
        auto words=terrain.edits;
        if(!words)return -1;
        unsigned primary=words[0],mask=primary-1u;
        auto rows=reinterpret_cast<const int4*>(words+4);
        unsigned row=stormSectionHash(key.x,key.y,key.z)&mask;
        for(unsigned i=0;i<8;i++,row=(row+1)&mask){
            auto v=rows[row];if(v.w<0)break;
            if(v.x==key.x&&v.y==key.y&&v.z==key.z)return v.w;
        }
        unsigned lo=primary,hi=lo+words[1];
        while(lo<hi){
            unsigned mid=(lo+hi)/2;auto v=rows[mid];
            int c=v.x<key.x?-1:v.x>key.x?1:v.y<key.y?-1:v.y>key.y?1:v.z<key.z?-1:v.z>key.z?1:0;
            if(c<0)lo=mid+1;else if(c>0)hi=mid;else return v.w;
        }
        return -1;
    }
    __device__ int lookup(int3 p){
        if(!terrain.rows||terrain.mask==0)return -1;
        if(hasPage&&same(cached,p))return page;
        cached=p;hasPage=true;page=-1;
        unsigned row=stormSectionHash(p.x,p.y,p.z)&terrain.mask;
        for(unsigned i=0;i<8;i++,row=(row+1)&terrain.mask){
            auto v=terrain.rows[row];
            if(v.w==-1)break;
            if(v.x==p.x&&v.y==p.y&&v.z==p.z){page=v.w;break;}
        }
        if(page==-1){
            unsigned lo=terrain.mask+1,hi=lo+terrain.overflow;
            while(lo<hi){unsigned mid=lo+(hi-lo)/2;auto v=terrain.rows[mid];
                int c=v.x<p.x?-1:v.x>p.x?1:v.y<p.y?-1:v.y>p.y?1:v.z<p.z?-1:v.z>p.z?1:0;
                if(c<0)lo=mid+1;else if(c>0)hi=mid;else{page=v.w;break;}}
        }
        return page;
    }
    __device__ bool solid(int3 p,int id) const{
        if(id<0&&id!=-3)return false;
        unsigned cell=unsigned((p.x&15)|((p.z&15)<<4)|((p.y&15)<<8));
        int edit=editPage(section(p));
        if(edit>=0){
            auto words=terrain.edits;
            unsigned at=4u+4u*(words[0]+words[1])+unsigned(edit)*128u+(cell>>5);
            if((words[at]&(1u<<(cell&31u)))!=0u)return false;
        }
        if(id==-3)return true;
        return (terrain.cells[unsigned(id)*128u+(cell>>5)]&(1u<<(cell&31u)))!=0;
    }
    __device__ bool editedProbe(float3 start,float3 end) const {
        if(!terrain.edits)return false;
        auto lo=section(block(vec3(fminf(start.x,end.x),fminf(start.y,end.y),fminf(start.z,end.z))));
        auto hi=section(block(vec3(fmaxf(start.x,end.x),fmaxf(start.y,end.y),fmaxf(start.z,end.z))));
        for(int x=lo.x;x<=hi.x;x++)for(int y=lo.y;y<=hi.y;y++)for(int z=lo.z;z<=hi.z;z++)
            if(editPage(make_int3(x,y,z))>=0)return true;
        return false;
    }
    __device__ bool editedProbeHit(float3 origin,float3 direction,float& distance,float3& normal){
        float dir[]{direction.x,direction.y,direction.z};
        float start[]{origin.x+direction.x*.0001f,origin.y+direction.y*.0001f,origin.z+direction.z*.0001f};
        int cell[3],step[3];float stride[3],edge[3];
        for(int axis=0;axis<3;axis++){
            cell[axis]=__float2int_rd(start[axis]);
            step[axis]=int(dir[axis]>0.f)-int(dir[axis]<0.f);
            stride[axis]=1.f/fmaxf(fabsf(dir[axis]),.000001f);
            edge[axis]=step[axis]?(cell[axis]+int(step[axis]>0)-start[axis])*step[axis]*stride[axis]:1e9f;
        }
        for(int i=0;i<16;i++){
            int axis=edge[0]<=edge[1]&&edge[0]<=edge[2]?0:edge[1]<=edge[2]?1:2;
            float at=edge[axis]+.0001f;
            if(at>distance)break;
            cell[axis]+=step[axis];edge[axis]+=stride[axis];
            auto point=make_int3(cell[0],cell[1],cell[2]);
            if(solid(point,lookup(section(point)))){
                distance=at;normal=make_float3(0,0,0);
                (axis==0?normal.x:axis==1?normal.y:normal.z)=-float(step[axis]);
                return true;
            }
        }
        return false;
    }
    __device__ StormBody body(vec3 feet){
        auto lo=block(feet+vec3(-.17499f,.00001f,-.17499f));
        auto hi=block(feet+vec3(.17499f,.59999f,.17499f));
        auto key=section(lo);
        bool single=same(key,section(hi));
        int shared=single?lookup(key):-4;
        if(single&&shared<0&&(shared!=-3||editPage(key)<0))return {shared==-3,shared!=-1};
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
        hitDown=false;
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
            if(probe.occupied){
                if(axis==1&&move<0.f){hitDown=true;impactPoint=position;}
                v=0.f;result|=StormTerrainClipped;
            }
            else position=next;
        }
        return result;
    }
};
