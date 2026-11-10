__device__ float dot(vec3 a,vec3 b) { return a.x*b.x+a.y*b.y+a.z*b.z; }
__device__ vec3 cross(vec3 a,vec3 b) {
    return vec3(a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x);
}
__device__ vec3 unit(vec3 v,vec3 fallback) {
    float speed=length(v);
    return speed>.000001f?v*(1.f/speed):fallback;
}
__device__ vec3 boundedTurn(vec3 from,vec3 to,float step) {
    float cosine=clamp(dot(from,to),-1.f,1.f);
    if(acosf(cosine)<=step)return to;
    vec3 side=to-from*cosine;
    if(length(side)<.00001f)side=cross(from,fabsf(from.y)<.9f?vec3(0,1,0):vec3(1,0,0));
    return unit(from*cosf(step)+unit(side,from)*sinf(step),from);
}
__device__ vec3 gatherVelocity(vec3 velocity,vec3 desired,float cap,float dt) {
    float oldSpeed=length(velocity);
    float speed=clamp(fminf(cap,length(desired)),fmaxf(0.f,oldSpeed-18.f*dt),oldSpeed+18.f*dt);
    auto direction=unit(desired,unit(velocity,vec3(0,0,1)));
    if(oldSpeed>.0001f&&speed>.0001f){
        float cosine=clamp((oldSpeed*oldSpeed+speed*speed-324.f*dt*dt)/(2.f*oldSpeed*speed),-1.f,1.f);
        direction=boundedTurn(velocity*(1.f/oldSpeed),direction,fminf(3.141592654f*dt,acosf(cosine)));
    }
    return direction*speed;
}

enum Corridor : int { CorridorUnknown=-1, CorridorFree=0, CorridorBlocked=1 };
struct NavigationResult { vec3 desired; bool altered, blocked, unknown; };

__device__ Corridor bodyTerrain(StormCollision& terrain,vec3 feet) {
    auto body=terrain.body(feet);
    return !body.complete?CorridorUnknown:body.occupied?CorridorBlocked:CorridorFree;
}
__device__ Corridor pathSegment(StormCollision& terrain,vec3 from,vec3 to) {
    auto displacement=to-from;
    int steps=int(ceilf(length(displacement)*4.f));
    if(steps>56)return CorridorBlocked;
    for(int i=1;i<=steps;i++){
        auto result=bodyTerrain(terrain,from+displacement*(float(i)/float(steps)));
        if(result!=CorridorFree)return result;
    }
    return CorridorFree;
}
__device__ float pathClearance(StormCollision& terrain,vec3 pos,vec3 dir,float reach) {
    float clear=0.f;
    for(int i=1;i<=8&&clear<reach;i++){
        float distance=fminf(float(i)*.25f,reach);
        if(bodyTerrain(terrain,pos+dir*distance)!=CorridorFree)break;
        clear=distance;
    }
    return clear;
}
__device__ vec3 routeCell(unsigned cell) {
    return vec3(float(cell&7),float((cell>>3)&7),float(cell>>6));
}
__device__ __noinline__ void findStormRoute(StormCollision& terrain,StormRoute& path,vec3 pos,vec3 target) {
    constexpr unsigned start=292, missing=512;
    auto origin=pos-vec3(4.f);
    auto offset=target-origin;
    bool inside=offset.x>=0.f&&offset.y>=0.f&&offset.z>=0.f&&offset.x<=7.f&&offset.y<=7.f&&offset.z<=7.f;
    float initial=length(pos-target);
    uint16_t parents[512]{};
    uint16_t queue[512];
    queue[0]=start;parents[start]=start+1;
    unsigned head=0,tail=1,end=missing;
    bool unknown=false;
    while(head<tail){
        unsigned cell=queue[head++];
        auto point=origin+routeCell(cell);
        float remaining=length(point-target);
        if((inside&&remaining<=1.f)||(!inside&&initial-remaining>=1.f)){
            auto toward=inside?target:point+unit(target-point,vec3(0.f))*2.f;
            auto result=pathSegment(terrain,point,toward);
            if(result==CorridorFree){end=cell;break;}
            unknown|=result==CorridorUnknown;
        }
        for(unsigned direction=0;direction<6;direction++){
            unsigned shift=(direction>>1)*3;
            unsigned coordinate=(cell>>shift)&7;
            int step=(direction&1)?-1:1;
            if((step<0&&coordinate==0)||(step>0&&coordinate==7))continue;
            unsigned next=unsigned(int(cell)+step*(1<<shift));
            if(parents[next])continue;
            auto result=pathSegment(terrain,point,origin+routeCell(next));
            unknown|=result==CorridorUnknown;
            if(result!=CorridorFree)continue;
            parents[next]=uint16_t(cell+1);
            queue[tail++]=uint16_t(next);
        }
    }
    path.origin=origin.row(0.f);
    path.goal=target.row(0.f);
    path.count=path.next=0;
    path.revision=terrain.terrain.revision;
    path.searched=true;
    path.noRoute=end==missing&&!unknown;
    if(end==missing)return;
    uint16_t turns[8];
    unsigned count=0;
    int lastDirection=0;
    while(end!=start){
        unsigned parent=parents[end]-1;
        int direction=int(end)-int(parent);
        if(direction!=lastDirection){
            if(count==8)return;
            turns[count++]=uint16_t(end);
        }
        lastDirection=direction;end=parent;
    }
    for(unsigned i=0;i<count;i++)path.waypoints[i]=turns[count-1-i];
    path.count=count;
}
__device__ NavigationResult navigateStorm(StormCollision& terrain,StormRoute& path,uint32_t* searches,
        uint64_t tick,unsigned member,vec3 pos,vec3 goal,vec3 desired,float cap) {
    float remaining=length(goal-pos);
    auto forward=unit(goal-pos,vec3(0.f));
    auto direct=pathSegment(terrain,pos,pos+forward*fminf(2.f,remaining));
    if(direct==CorridorFree){
        path.count=0;path.blockedSeconds=0.f;path.searched=path.noRoute=false;
        return {desired,false,false,false};
    }
    if(path.revision!=terrain.terrain.revision||length(goal-vec3(path.goal))>2.f){
        path.count=0;path.searched=path.noRoute=false;
    }
    if(direct==CorridorBlocked){
        path.blockedSeconds+=.05f;
        if(path.next>=path.count&&path.blockedSeconds>=.1f&&tick>=path.retryAt
            &&atomicAdd(searches,1u)<128u){
            findStormRoute(terrain,path,pos,goal);
            path.retryAt=tick+10u;
        }
    }else{
        path.blockedSeconds=0.f;
        path.searched=path.noRoute=false;
    }
    for(unsigned i=path.count;i>path.next;i--){
        auto target=vec3(path.origin)+routeCell(path.waypoints[i-1]);
        auto delta=target-pos;
        float distance=length(delta);
        if(pathSegment(terrain,pos,target)!=CorridorFree)continue;
        if(distance<=.3f){path.next=i;break;}
        path.next=i-1;
        float speed=fminf(cap,sqrtf(36.f*fmaxf(0.f,distance-.15f)));
        return {delta*(speed/distance),true,true,false};
    }
    float speed=length(desired);
    if(speed<.00001f)return {vec3(0.f),true,direct==CorridorBlocked,direct==CorridorUnknown};
    auto velocityDir=desired*(1.f/speed);
    float reach=fminf(2.f,fmaxf(.25f,speed*speed/36.f+.1f));
    auto side=unit(cross(velocityDir,fabsf(velocityDir.y)<.9f?vec3(0,1,0):vec3(1,0,0)),vec3(1,0,0));
    auto up=unit(cross(side,velocityDir),vec3(0,1,0));
    vec3 options[]={velocityDir,velocityDir+up,velocityDir-up,velocityDir+side,velocityDir-side,side,side*(-1.f),vec3(path.heading)};
    vec3 best(0.f);
    float bestClear=0.f,bestScore=-100.f;
    for(unsigned i=0;i<8;i++){
        auto candidate=unit(options[i],vec3(0.f));
        if(length(candidate)<.5f)continue;
        float clear=pathClearance(terrain,pos,candidate,reach);
        if(clear<.25f)continue;
        float tie=float((member*1664525u+i*1013904223u)&255u)*(.001f/255.f);
        float score=.65f*clear/reach+.25f*dot(candidate,forward)+.1f*dot(candidate,vec3(path.heading))+tie;
        if(score>bestScore+.025f){best=candidate;bestClear=clear;bestScore=score;}
    }
    path.heading=best.row(0.f);
    float allowed=fminf(fminf(speed,cap),sqrtf(36.f*fmaxf(0.f,bestClear-.1f)));
    return {best*allowed,true,direct==CorridorBlocked,direct==CorridorUnknown};
}

__device__ float escapeDistance(StormCollision& terrain,vec3 origin,vec3 direction,float limit) {
    int steps=int(ceilf(limit*4.f));
    for(int i=1;i<=steps;i++){
        float distance=fminf(float(i)*.25f,limit);
        auto result=bodyTerrain(terrain,origin+direction*distance);
        if(result==CorridorUnknown)return 0.f;
        if(result==CorridorFree)return distance;
    }
    return 0.f;
}
__device__ __noinline__ bool escapeStormBody(StormCollision& terrain,vec3& origin,vec3& velocity) {
    auto lo=StormCollision::block(origin+vec3(-.17499f,.00001f,-.17499f));
    auto hi=StormCollision::block(origin+vec3(.17499f,.59999f,.17499f));
    float limits[6]{};
    for(int x=lo.x;x<=hi.x;x++)for(int y=lo.y;y<=hi.y;y++)for(int z=lo.z;z<=hi.z;z++){
        auto block=make_int3(x,y,z);
        int page=terrain.lookup(StormCollision::section(block));
        if(page==-1)return false;
        if(!terrain.solid(block,page))continue;
        limits[0]=fmaxf(limits[0],origin.x+.175f-float(x)+.00002f);
        limits[1]=fmaxf(limits[1],float(x+1)-origin.x+.175f+.00002f);
        limits[2]=fmaxf(limits[2],origin.y+.6f-float(y)+.00002f);
        limits[3]=fmaxf(limits[3],float(y+1)-origin.y+.00002f);
        limits[4]=fmaxf(limits[4],origin.z+.175f-float(z)+.00002f);
        limits[5]=fmaxf(limits[5],float(z+1)-origin.z+.175f+.00002f);
    }
    vec3 best(0.f);
    float nearest=9.f;
    for(int i=0;i<6;i++){
        float sign=(i&1)==0?-1.f:1.f;
        vec3 direction=i<2?vec3(sign,0,0):i<4?vec3(0,sign,0):vec3(0,0,sign);
        float distance=escapeDistance(terrain,origin,direction,limits[i]);
        if(distance>0.f&&distance<nearest){nearest=distance;best=direction;}
    }
    if(nearest>8.f){
        for(int i=0;i<27;i++){
            if(i==13)continue;
            auto direction=unit(vec3(float(i%3-1),float((i/3)%3-1),float(i/9-1)),vec3(0.f));
            float distance=escapeDistance(terrain,origin,direction,8.f);
            if(distance>0.f&&distance<nearest){nearest=distance;best=direction;}
        }
    }
    if(nearest>8.f)return false;
    origin=origin+best*nearest;
    velocity=velocity-best*dot(velocity,best);
    return true;
}
