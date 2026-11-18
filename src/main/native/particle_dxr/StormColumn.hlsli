static const float columnSpeed=6.f,columnOrbitSpeed=3.f;
static const uint columnDescent=2,columnBloom=4,columnExit=5,columnDone=6;

float columnMix(float a,float b,float t){return a+(b-a)*t;}
float columnHeight(float r){return .0625f*r*r;}
float columnArc(float r) {
    float x=r*r/64.f;
    return r*(1.f+x*(1.f/6.f+x*(-1.f/40.f+x*(1.f/112.f+x*(-5.f/1152.f+x*(7.f/2816.f))))));
}
float columnRadius(float arc) {
    float r=clamp(arc,0.f,4.f);
    for(int i=0;i<2;i++)r=clamp(r-(columnArc(r)-arc)/sqrt(1.f+r*r/64.f),0.f,4.f);
    return r;
}
float columnSmooth(float u){return quintic(clamp(u,0.f,1.f));}
float columnOrbitRadius(uint rank){return .9f+.3f*float(rank/4u)/15.f;}
float columnOrbitAngle(StormColumn column,uint rank,float elapsed) {
    return float(rank&3u)*1.570796327f+float(rank/4u)*.35f+float(column.seed)*.0001f
        +(columnOrbitSpeed/columnOrbitRadius(rank))*elapsed;
}
vec3 columnGather(StormColumn column,uint rank,float elapsed) {
    float a=columnOrbitAngle(column,rank,elapsed),r=columnOrbitRadius(rank);
    return vec3(column.landingTop.x+cos(a)*r,column.landingTop.w+(float(rank/4u)-7.5f)*.7f,
        column.landingTop.z+sin(a)*r);
}
vec3 columnOrbitVelocity(StormColumn column,uint rank,float elapsed) {
    float a=columnOrbitAngle(column,rank,elapsed);
    return vec3(-sin(a)*columnOrbitSpeed,0.f,cos(a)*columnOrbitSpeed);
}
vec3 columnCurvePoint(StormColumnCurve p,float u) {
    float h0=u*(1.f-u)*(1.f-u),h1=u*u*(u-1.f),s=columnSmooth(u);
    float radius=max(.2f,columnMix(p.radius,p.root,s)+p.radial*h0+.35f*h1);
    float angle=columnMix(p.angle,p.endAngle,s)+p.angular*h0;
    return vec3(cos(angle)*radius,columnMix(p.height,p.endHeight,s)+p.vertical*h0+.35f*p.root*.125f*h1,
        sin(angle)*radius);
}
float columnArcGrid(float t,float strength) {
    return t+strength*t*(1.f-t)*(1.f-2.f*t)*(1.f-7.f*t+7.f*t*t);
}
float columnParameter(StormColumnMember member,float distance) {
    if(distance<=0.f)return 0.f;
    if(distance>=member.arc[64])return 1.f;
    uint lo=0,hi=64;
    for(int i=0;i<6;i++){
        uint mid=(lo+hi)/2u;
        if(member.arc[mid]<distance)lo=mid;else hi=mid;
    }
    float span=max(member.arc[hi]-member.arc[lo],.000001f);
    float warp=member.curve.arcWarp;
    return columnMix(columnArcGrid(float(lo)/64.f,warp),columnArcGrid(float(hi)/64.f,warp),
        clamp((distance-member.arc[lo])/span,0.f,1.f));
}
void initColumnCurve(inout StormColumnMember state,StormColumn column,uint rank,
        vec3 position,vec3 direction,float begin) {
    direction=unit(direction,unit(columnOrbitVelocity(column,rank,begin),vec3(0,0,1)));
    vec3 start=position-vec3(column.landingTop);
    float radius=max(sqrt(start.x*start.x+start.z*start.z),.2f);
    float root=sqrt(columnMix(.04f,1.96f,(float(rank)+.5f)/64.f));
    float angle=atan2(start.z,start.x);
    float endAngle=angle+.7f*max(column.touch+float(rank)*.06f-column.assemble,.1f);
    float offset=float((rank*27u)&63u)*.09817477042f+float(column.seed)*.0001f-endAngle;
    endAngle+=offset-6.2831853f*floor((offset+3.14159265f)/6.2831853f);
    float c=cos(angle),s=sin(angle);
    float w=clamp((max(0.f,start.y)/radius-1.f)/3.f,0.f,1.f);
    StormColumnCurve curve={radius,root,angle,endAngle,start.y,columnHeight(root),
        clamp((direction.x*c+direction.z*s)*4.f,-.25f*radius,.25f*radius),
        (-direction.x*s+direction.z*c)*4.f/radius,direction.y*4.f,.1f+.1f*w*w*(3.f-2.f*w)};
    state.curve=curve;
    vec3 previous=start;
    float lengthTotal=0.f;
    state.arc[0]=0.f;
    for(uint i=1;i<=128;i++){
        vec3 curvePoint=columnCurvePoint(state.curve,columnArcGrid(float(i)/128.f,state.curve.arcWarp));
        lengthTotal+=length(curvePoint-previous);
        if((i&1u)==0u)state.arc[i/2u]=lengthTotal;
        previous=curvePoint;
    }
    state.distance=0.f;
    state.phase=columnDescent;
    state.tangent=float4(direction,0.f);
    state.descentStart=begin;state.descentOrigin=float4(position,0.f);state.descentDirection=float4(direction,0.f);
}
bool columnFloor(inout StormCollision terrain,vec3 feet) {
    int3 above=voxelBlock(feet+vec3(.0001f,.1001f,.0001f));
    int3 below=voxelBlock(feet+vec3(.0001f,-.1999f,.0001f));
    int upper=terrain.lookup(voxelSection(above)),lower=terrain.lookup(voxelSection(below));
    return upper!=-1&&lower!=-1&&!terrain.solid(above,upper)&&terrain.solid(below,lower);
}
void startColumnBloom(inout StormColumnMember member,StormColumn column,vec3 position,float time) {
    vec3 relative=position-vec3(column.landingTop);
    member.touchdown=time;
    member.bloomRadius=clamp(sqrt(relative.x*relative.x+relative.z*relative.z),0.f,4.f);
    member.bloomAngle=atan2(relative.z,relative.x);
    member.distance=0.f;
    member.phase=columnBloom;
}

struct ColumnMotion {
    vec3 velocity,exitVelocity;
    vec3 bloomPoint;
    float marker,speedCap;
    bool engaged,exited,bloomStarted;
};
ColumnMotion advanceColumn(inout StormColumnMember member,StormColumn column,uint rank,
        inout StormCollision terrain,vec3 position,vec3 velocity,vec3 home,vec3 homeVelocity,float now,float step) {
    ColumnMotion result=(ColumnMotion)0; result.speedCap=6.f;
    if(now<0.f||now-step>=column.end||!column.state)return result;
    result.engaged=true;
    if(column.state==2u){result.marker=3.f;return result;}
    if(member.id!=column.id||member.identity!=column.identity){
        member=(StormColumnMember)0;
        member.id=column.id;member.identity=column.identity;
        member.touchdown=member.release=-1.f;
        member.origin=float4(position,0.f);member.initialVelocity=float4(velocity,0.f);
    }
    if(member.phase==columnDone){result.engaged=false;return result;}
    if(member.phase==0u){
        if(now<column.assemble){
            float duration=max(column.assemble,.1f),u=clamp(now/duration,0.f,1.f);
            vec3 target=blend(vec3(member.origin),columnGather(column,rank,now),columnSmooth(u))
                +vec3(member.initialVelocity)*(duration*u*(1.f-u)*(1.f-u)*(1.f-u));
            result.velocity=(target-position)*(1.f/step);
            result.marker=3.f;result.speedCap=8.f;
            return result;
        }
        float begin=now-step;
        vec3 direction=velocity;
        if(length(position-columnGather(column,rank,begin))<=.4f&&length(velocity-columnOrbitVelocity(column,rank,begin))<=1.f){
            vec3 radial=unit(vec3(position.x-column.landingTop.x,0.f,position.z-column.landingTop.z),vec3(0.f));
            if(length(radial)>.5f)direction=vec3(-radial.z*columnOrbitSpeed,-sqrt(27.f),radial.x*columnOrbitSpeed);
        }
        initColumnCurve(member,column,rank,position,direction,begin);
    }
    float remaining=columnSpeed*step;
    vec3 curvePoint=position,motion=0;
    if(member.phase==columnDescent&&columnFloor(terrain,position)&&frac(position.y)<.04f){
        startColumnBloom(member,column,position,now-step);
        result.bloomStarted=true;result.bloomPoint=position;
    }
    if(member.phase==columnDescent){
        float total=member.arc[64],move=min(remaining,max(0.f,total-member.distance));
        vec3 before=columnCurvePoint(member.curve,columnParameter(member,member.distance));
        vec3 after=columnCurvePoint(member.curve,columnParameter(member,member.distance+move));
        vec3 direction=unit(after-before,vec3(0.f));
        motion=direction*move;curvePoint=position+motion;
        remaining-=move;member.distance+=move;
        if(length(direction)>.5f)member.tangent=float4(direction,0.f);
        if(member.distance<total-.00001f){
            result.marker=4.f+clamp(member.distance/max(total,.00001f),0.f,.99999f);
            result.velocity=motion*(1.f/step);
            return result;
        }
        vec3 local=curvePoint-vec3(column.landingTop);
        if(columnFloor(terrain,curvePoint)&&local.x*local.x+local.z*local.z<=16.f){
            startColumnBloom(member,column,curvePoint,now-remaining/columnSpeed);
            result.bloomStarted=true;result.bloomPoint=curvePoint;
        }else{
            member.phase=columnExit;
            member.release=now-remaining/columnSpeed;
        }
    }
    if(member.phase==columnBloom){
        float start=columnArc(member.bloomRadius),total=columnArc(4.f)-start;
        float move=min(remaining,max(0.f,total-member.distance));
        float r=columnRadius(start+member.distance),next=columnRadius(start+member.distance+move);
        float c=cos(member.bloomAngle),s=sin(member.bloomAngle);
        motion=motion+vec3(c*(next-r),columnHeight(next)-columnHeight(r),s*(next-r));curvePoint=position+motion;
        member.tangent=float4(unit(vec3(c,next*.125f,s),vec3(0,0,1)),0.f);
        remaining-=move;member.distance+=move;
        if(member.distance<total-.00001f){
            result.marker=-1.f-clamp(member.distance/max(total,.00001f),0.f,1.f);
            result.velocity=motion*(1.f/step);
            return result;
        }
        member.phase=columnExit;
        member.release=now-remaining/columnSpeed;
    }
    if(member.phase==columnExit){
        float age=max(0.f,now-member.release);
        float available=max(0.f,.5f-max(0.f,now-step-member.release));
        float move=min(remaining,available*columnSpeed);
        vec3 direction=unit(vec3(member.tangent),vec3(0,0,1));
        if(length(home-curvePoint)>.00001f)direction=boundedTurn(direction,unit(home-curvePoint,direction),
            2.5f*min(step,.5f)*columnSmooth(age/.5f));
        member.tangent=float4(direction,0.f);
        motion=motion+direction*move;curvePoint=position+motion;remaining-=move;
        result.marker=5.f;
        if(age>=.5f){
            float ordinary=remaining/columnSpeed;
            vec3 finalVelocity=direction*columnSpeed;
            if(ordinary>0.f){
                finalVelocity=finalVelocity+((home-curvePoint)*24.f+(homeVelocity-finalVelocity)*6.f)*ordinary;
                float speed=length(finalVelocity);
                if(speed>columnSpeed)finalVelocity=finalVelocity*(columnSpeed/speed);
                motion=motion+finalVelocity*ordinary;
            }
            result.exited=true;
            result.exitVelocity=finalVelocity;
            result.marker=6.f;
            member.phase=columnDone;
        }
    }
    result.velocity=motion*(1.f/step);
    return result;
}

float columnLocalClock(float absolute,float start) {
    float d=absolute-start;
    return d< -52428.8f?d+104857.6f:d>52428.8f?d-104857.6f:d;
}
void restoreColumn(inout StormColumnMember member,StormColumn column,uint rank,
        StormColumnRestore snapshot,inout vec3 position,inout vec3 velocity) {
    
    vec3 base=vec3(column.landingTop);

    position=base+vec3(snapshot.rows[0][1],snapshot.rows[0][2],snapshot.rows[0][3]);velocity=vec3(snapshot.rows[1][0],snapshot.rows[1][1],snapshot.rows[1][2]);
    member=(StormColumnMember)0;member.id=column.id;member.identity=column.identity;
    member.phase=uint(snapshot.rows[0][0]);member.restoreToken=column.restoreToken;member.replaying=true;
    if(member.phase==columnDescent)initColumnCurve(member,column,rank,base+vec3(snapshot.rows[4][1],snapshot.rows[4][2],snapshot.rows[4][3]),vec3(snapshot.rows[5][0],snapshot.rows[5][1],snapshot.rows[5][2]),
        columnLocalClock(snapshot.rows[1][3],column.start));
    member.touchdown=snapshot.rows[2][0]<0.f?-1.f:columnLocalClock(snapshot.rows[2][0],column.start);
    member.release=snapshot.rows[2][1]<0.f?-1.f:columnLocalClock(snapshot.rows[2][1],column.start);
    member.distance=snapshot.rows[2][2];
    member.origin=float4(base+vec3(snapshot.rows[2][3],snapshot.rows[3][0],snapshot.rows[3][1]),0.f); member.initialVelocity=float4(vec3(snapshot.rows[3][2],snapshot.rows[3][3],snapshot.rows[4][0]),0.f);
    member.tangent=float4(vec3(snapshot.rows[5][3],snapshot.rows[6][0],snapshot.rows[6][1]),0.f);member.bloomRadius=snapshot.rows[6][2];member.bloomAngle=snapshot.rows[6][3];
    if(member.phase!=0u){
        member.descentStart=columnLocalClock(snapshot.rows[1][3],column.start);
        member.descentOrigin=float4(base+snapshot.rows[4].yzw,0.f); member.descentDirection=float4(snapshot.rows[5].xyz,0.f);
    }
    member.replayCursor=column.restoreElapsed;
}

void columnContact(uint columnSlot,StormColumn column,StormColumnMember state,
        StormTick tick,vec3 before,vec3 after,float now,float step,uint terrainState) {
    uint target=asuint(tick.targetAfter.w);
    if(target==0||state.touchdown<0.f||(terrainState&(StormCoverageUnknown|StormDiscontinuity|StormEmbedded))!=0u)return;
    if(batches.Load(columnBatchOffset(columnSlot)+32u)!=0u)return;
    float end=state.release>=0.f?min(column.end,state.release):column.end;
    float first=max(0.f,(state.touchdown-now+step)/step);
    float last=min(1.f,(end-now+step)/step);
    if(first>last||end<=state.touchdown||now-step>=end)return;
    vec3 a=before-vec3(column.landingTop),b=after-vec3(column.landingTop);
    if(!clipSphereRange(vec3(a.x,0.f,a.z),vec3(b.x,0.f,b.z),4.0001f,first,last)
        ||!clipSphereRange(before-vec3(tick.targetBefore),after-vec3(tick.targetAfter),contactRadius,first,last))return;
    float age=now+(first-1.f)*step;
    if(age>=end)return;
    uint oldTarget;
    batches.InterlockedCompareExchange(columnBatchOffset(columnSlot)+32u,0u,target,oldTarget);
    if(oldTarget!=0u)return;
    float seconds=tick.seconds+(first-1.f)*step;
    batches.Store4(columnBatchOffset(columnSlot)+48u,asuint(float4(blend(before,after,first),seconds<0.f?seconds+104857.6f:seconds)));
}
