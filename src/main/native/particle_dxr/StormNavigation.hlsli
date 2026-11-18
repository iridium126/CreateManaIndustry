float3 unit(float3 v,float3 fallback) {
    float speed=length(v);
    return speed>.000001f?v*(1.f/speed):fallback;
}
float3 boundedTurn(float3 from,float3 to,float step) {
    float cosine=clamp(dot(from,to),-1.f,1.f);
    if(acos(cosine)<=step)return to;
    float3 side=to-from*cosine;
    if(length(side)<.00001f)side=cross(from,abs(from.y)<.9f?float3(0,1,0):float3(1,0,0));
    return unit(from*cos(step)+unit(side,from)*sin(step),from);
}
float3 gatherVelocity(float3 velocity,float3 desired,float cap,float dt) {
    float oldSpeed=length(velocity);
    float speed=clamp(min(cap,length(desired)),max(0.f,oldSpeed-18.f*dt),oldSpeed+18.f*dt);
    float3 direction=unit(desired,unit(velocity,float3(0,0,1)));
    if(oldSpeed>.0001f&&speed>.0001f){
        float cosine=clamp((oldSpeed*oldSpeed+speed*speed-324.f*dt*dt)/(2.f*oldSpeed*speed),-1.f,1.f);
        direction=boundedTurn(velocity*(1.f/oldSpeed),direction,min(3.141592654f*dt,acos(cosine)));
    }
    return direction*speed;
}

static const int CorridorUnknown=-1, CorridorFree=0, CorridorBlocked=1;
struct NavigationResult { float3 desired; bool altered, blocked, unknown; };

int bodyTerrain(inout StormCollision terrain,float3 feet) {
    StormBody body=terrain.body(feet);
    return !body.complete?CorridorUnknown:body.occupied?CorridorBlocked:CorridorFree;
}
int pathSegment(inout StormCollision terrain,float3 from,float3 to) {
    float3 displacement=to-from;
    int steps=int(ceil(length(displacement)*4.f));
    if(steps>56)return CorridorBlocked;
    for(int i=1;i<=steps;i++){
        int result=bodyTerrain(terrain,from+displacement*(float(i)/float(steps)));
        if(result!=CorridorFree)return result;
    }
    return CorridorFree;
}
float pathClearance(inout StormCollision terrain,float3 pos,float3 dir,float reach) {
    float clear=0.f;
    for(int i=1;i<=8&&clear<reach;i++){
        float distance=min(float(i)*.25f,reach);
        if(bodyTerrain(terrain,pos+dir*distance)!=CorridorFree)break;
        clear=distance;
    }
    return clear;
}
float3 routeCell(uint cell) {
    return float3(float(cell&7),float((cell>>3)&7),float(cell>>6));
}
void findStormRoute(inout StormCollision terrain,inout StormRoute path,float3 pos,float3 target) {
    static const uint start=292, missing=512;
    float3 origin=pos-(float3)(4.f);
    float3 offset=target-origin;
    bool inside=offset.x>=0.f&&offset.y>=0.f&&offset.z>=0.f&&offset.x<=7.f&&offset.y<=7.f&&offset.z<=7.f;
    float initial=length(pos-target);
    uint parents[512]=(uint[512])0;
    uint queue[512];
    queue[0]=start;parents[start]=start+1;
    uint head=0,tail=1,end=missing;
    bool unknown=false;
    while(head<tail){
        uint cell=queue[head++];
        float3 cellPosition=origin+routeCell(cell);
        float remaining=length(cellPosition-target);
        if((inside&&remaining<=1.f)||(!inside&&initial-remaining>=1.f)){
            float3 toward=inside?target:cellPosition+unit(target-cellPosition,(float3)(0.f))*2.f;
            int result=pathSegment(terrain,cellPosition,toward);
            if(result==CorridorFree){end=cell;break;}
            unknown|=result==CorridorUnknown;
        }
        for(uint direction=0;direction<6;direction++){
            uint shift=(direction>>1)*3;
            uint coordinate=(cell>>shift)&7;
            int step=(direction&1)?-1:1;
            if((step<0&&coordinate==0)||(step>0&&coordinate==7))continue;
            uint next=uint(int(cell)+step*(int(1u<<shift)));
            if(parents[next])continue;
            int result=pathSegment(terrain,cellPosition,origin+routeCell(next));
            unknown|=result==CorridorUnknown;
            if(result!=CorridorFree)continue;
            parents[next]=uint(cell+1);
            queue[tail++]=uint(next);
        }
    }
    path.origin=float4(origin,0.f);
    path.goal=float4(target,0.f);
    path.count=path.next=0;
    path.revision=terrainRevision;
    path.searched=true;
    path.noRoute=end==missing&&!unknown;
    if(end==missing)return;
    uint turns[8];
    uint count=0;
    int lastDirection=0;
    while(end!=start){
        uint parent=parents[end]-1;
        int direction=int(end)-int(parent);
        if(direction!=lastDirection){
            if(count==8)return;
            turns[count++]=uint(end);
        }
        lastDirection=direction;end=parent;
    }
    for(uint i=0;i<count;i++)path.waypoints[i]=turns[count-1-i];
    path.count=count;
}
NavigationResult navigateStorm(inout StormCollision terrain,inout StormRoute path,
        uint64_t tick,uint member,float3 pos,float3 goal,float3 desired,float cap) {
    float remaining=length(goal-pos);
    float3 forward=unit(goal-pos,(float3)(0.f));
    int direct=pathSegment(terrain,pos,pos+forward*min(2.f,remaining));
    if(direct==CorridorFree){
        path.count=0;path.blockedSeconds=0.f;path.searched=path.noRoute=false;
        { NavigationResult result={desired,false,false,false}; return result; }
    }
    if(path.revision!=terrainRevision||length(goal-path.goal.xyz)>2.f){
        path.count=0;path.searched=path.noRoute=false;
    }
    if(direct==CorridorBlocked){
        path.blockedSeconds+=.05f;
        if(path.next>=path.count&&path.blockedSeconds>=.1f&&tick>=path.retryAt
            &&claimSearch(0,128)){
            findStormRoute(terrain,path,pos,goal);
            path.retryAt=tick+10u;
        }
    }else{
        path.blockedSeconds=0.f;
        path.searched=path.noRoute=false;
    }
    for(uint i=path.count;i>path.next;i--){
        float3 target=path.origin.xyz+routeCell(path.waypoints[i-1]);
        float3 delta=target-pos;
        float distance=length(delta);
        if(pathSegment(terrain,pos,target)!=CorridorFree)continue;
        if(distance<=.3f){path.next=i;break;}
        path.next=i-1;
        float speed=min(cap,sqrt(36.f*max(0.f,distance-.15f)));
        { NavigationResult result={delta*(speed/distance),true,true,false}; return result; }
    }
    float speed=length(desired);
    if(speed<.00001f){ NavigationResult result={(float3)(0.f),true,direct==CorridorBlocked,direct==CorridorUnknown}; return result; }
    float3 velocityDir=desired*(1.f/speed);
    float reach=min(2.f,max(.25f,speed*speed/36.f+.1f));
    float3 side=unit(cross(velocityDir,abs(velocityDir.y)<.9f?float3(0,1,0):float3(1,0,0)),float3(1,0,0));
    float3 up=unit(cross(side,velocityDir),float3(0,1,0));
    float3 options[]={velocityDir,velocityDir+up,velocityDir-up,velocityDir+side,velocityDir-side,side,side*(-1.f),path.heading.xyz};
    float3 best=0;
    float bestClear=0.f,bestScore=-100.f;
    for(uint i=0;i<8;i++){
        float3 candidate=unit(options[i],(float3)(0.f));
        if(length(candidate)<.5f)continue;
        float clear=pathClearance(terrain,pos,candidate,reach);
        if(clear<.25f)continue;
        float tie=float((member*1664525u+i*1013904223u)&255u)*(.001f/255.f);
        float score=.65f*clear/reach+.25f*dot(candidate,forward)+.1f*dot(candidate,path.heading.xyz)+tie;
        if(score>bestScore+.025f){best=candidate;bestClear=clear;bestScore=score;}
    }
    path.heading=float4(best,0.f);
    float allowed=min(min(speed,cap),sqrt(36.f*max(0.f,bestClear-.1f)));
    { NavigationResult result={best*allowed,true,direct==CorridorBlocked,direct==CorridorUnknown}; return result; }
}

float escapeDistance(inout StormCollision terrain,float3 origin,float3 direction,float limit) {
    int steps=int(ceil(limit*4.f));
    for(int i=1;i<=steps;i++){
        float distance=min(float(i)*.25f,limit);
        int result=bodyTerrain(terrain,origin+direction*distance);
        if(result==CorridorUnknown)return 0.f;
        if(result==CorridorFree)return distance;
    }
    return 0.f;
}
bool escapeStormBody(inout StormCollision terrain,inout float3 origin,inout float3 velocity) {
    int3 lo=voxelBlock(origin+float3(-.17499f,.00001f,-.17499f));
    int3 hi=voxelBlock(origin+float3(.17499f,.59999f,.17499f));
    float limits[6]=(float[6])0;
    for(int x=lo.x;x<=hi.x;x++)for(int y=lo.y;y<=hi.y;y++)for(int z=lo.z;z<=hi.z;z++){
        int3 block=int3(x,y,z);
        int page=terrain.lookup(voxelSection(block));
        if(page==-1)return false;
        if(!terrain.solid(block,page))continue;
        limits[0]=max(limits[0],origin.x+.175f-float(x)+.00002f);
        limits[1]=max(limits[1],float(x+1)-origin.x+.175f+.00002f);
        limits[2]=max(limits[2],origin.y+.6f-float(y)+.00002f);
        limits[3]=max(limits[3],float(y+1)-origin.y+.00002f);
        limits[4]=max(limits[4],origin.z+.175f-float(z)+.00002f);
        limits[5]=max(limits[5],float(z+1)-origin.z+.175f+.00002f);
    }
    float3 best=0;
    float nearest=9.f;
    for(int i=0;i<6;i++){
        float sign=(i&1)==0?-1.f:1.f;
        float3 direction=i<2?float3(sign,0,0):i<4?float3(0,sign,0):float3(0,0,sign);
        float distance=escapeDistance(terrain,origin,direction,limits[i]);
        if(distance>0.f&&distance<nearest){nearest=distance;best=direction;}
    }
    if(nearest>8.f){
        for(int i=0;i<27;i++){
            if(i==13)continue;
            float3 direction=unit(float3(float(i%3-1),float((i/3)%3-1),float(i/9-1)),(float3)(0.f));
            float distance=escapeDistance(terrain,origin,direction,8.f);
            if(distance>0.f&&distance<nearest){nearest=distance;best=direction;}
        }
    }
    if(nearest>8.f)return false;
    origin=origin+best*nearest;
    velocity=velocity-best*dot(velocity,best);
    return true;
}
