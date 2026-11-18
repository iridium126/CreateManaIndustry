constexpr float columnSpeed=6.f,columnOrbitSpeed=3.f;
constexpr unsigned columnDescent=2,columnBloom=4,columnExit=5,columnDone=6;

__device__ float columnMix(float a,float b,float t){return a+(b-a)*t;}
__device__ float columnHeight(float r){return .0625f*r*r;}
__device__ float columnArc(float r) {
    float x=r*r/64.f;
    return r*(1.f+x*(1.f/6.f+x*(-1.f/40.f+x*(1.f/112.f+x*(-5.f/1152.f+x*(7.f/2816.f))))));
}
__device__ float columnRadius(float arc) {
    float r=clamp(arc,0.f,4.f);
    for(int i=0;i<2;i++)r=clamp(r-(columnArc(r)-arc)/sqrtf(1.f+r*r/64.f),0.f,4.f);
    return r;
}
__device__ float columnSmooth(float u){return quintic(clamp(u,0.f,1.f));}
__device__ float columnOrbitRadius(unsigned rank){return .9f+.3f*float(rank/4u)/15.f;}
__device__ float columnOrbitAngle(const StormColumn& column,unsigned rank,float elapsed) {
    return float(rank&3u)*1.570796327f+float(rank/4u)*.35f+float(column.seed)*.0001f
        +(columnOrbitSpeed/columnOrbitRadius(rank))*elapsed;
}
__device__ vec3 columnGather(const StormColumn& column,unsigned rank,float elapsed) {
    float a=columnOrbitAngle(column,rank,elapsed),r=columnOrbitRadius(rank);
    return vec3(column.landingTop.x+cosf(a)*r,column.landingTop.w+(float(rank/4u)-7.5f)*.7f,
        column.landingTop.z+sinf(a)*r);
}
__device__ vec3 columnOrbitVelocity(const StormColumn& column,unsigned rank,float elapsed) {
    float a=columnOrbitAngle(column,rank,elapsed);
    return vec3(-sinf(a)*columnOrbitSpeed,0.f,cosf(a)*columnOrbitSpeed);
}
__device__ vec3 columnCurvePoint(const StormColumnCurve& p,float u) {
    float h0=u*(1.f-u)*(1.f-u),h1=u*u*(u-1.f),s=columnSmooth(u);
    float radius=fmaxf(.2f,columnMix(p.radius,p.root,s)+p.radial*h0+.35f*h1);
    float angle=columnMix(p.angle,p.endAngle,s)+p.angular*h0;
    return vec3(cosf(angle)*radius,columnMix(p.height,p.endHeight,s)+p.vertical*h0+.35f*p.root*.125f*h1,
        sinf(angle)*radius);
}
__device__ float columnArcGrid(float t,float strength) {
    return t+strength*t*(1.f-t)*(1.f-2.f*t)*(1.f-7.f*t+7.f*t*t);
}
__device__ float columnParameter(const StormColumnMember& member,float distance) {
    if(distance<=0.f)return 0.f;
    if(distance>=member.arc[64])return 1.f;
    unsigned lo=0,hi=64;
    for(int i=0;i<6;i++){
        unsigned mid=(lo+hi)/2u;
        if(member.arc[mid]<distance)lo=mid;else hi=mid;
    }
    float span=fmaxf(member.arc[hi]-member.arc[lo],.000001f);
    float warp=member.curve.arcWarp;
    return columnMix(columnArcGrid(float(lo)/64.f,warp),columnArcGrid(float(hi)/64.f,warp),
        clamp((distance-member.arc[lo])/span,0.f,1.f));
}
__device__ void initColumnCurve(StormColumnMember& state,const StormColumn& column,unsigned rank,
        vec3 position,vec3 direction,float begin) {
    direction=unit(direction,unit(columnOrbitVelocity(column,rank,begin),vec3(0,0,1)));
    auto start=position-vec3(column.landingTop);
    float radius=fmaxf(sqrtf(start.x*start.x+start.z*start.z),.2f);
    float root=sqrtf(columnMix(.04f,1.96f,(float(rank)+.5f)/64.f));
    float angle=atan2f(start.z,start.x);
    float endAngle=angle+.7f*fmaxf(column.touch+float(rank)*.06f-column.assemble,.1f);
    float offset=float((rank*27u)&63u)*.09817477042f+float(column.seed)*.0001f-endAngle;
    endAngle+=offset-6.2831853f*floorf((offset+3.14159265f)/6.2831853f);
    float c=cosf(angle),s=sinf(angle);
    float w=clamp((fmaxf(0.f,start.y)/radius-1.f)/3.f,0.f,1.f);
    state.curve={radius,root,angle,endAngle,start.y,columnHeight(root),
        clamp((direction.x*c+direction.z*s)*4.f,-.25f*radius,.25f*radius),
        (-direction.x*s+direction.z*c)*4.f/radius,direction.y*4.f,.1f+.1f*w*w*(3.f-2.f*w)};
    vec3 previous=start;
    float lengthTotal=0.f;
    state.arc[0]=0.f;
    for(unsigned i=1;i<=128;i++){
        auto point=columnCurvePoint(state.curve,columnArcGrid(float(i)/128.f,state.curve.arcWarp));
        lengthTotal+=length(point-previous);
        if((i&1u)==0u)state.arc[i/2u]=lengthTotal;
        previous=point;
    }
    state.distance=0.f;
    state.phase=columnDescent;
    state.tangent=direction.row(0.f);
    state.descentStart=begin;state.descentOrigin=position.row(0.f);state.descentDirection=direction.row(0.f);
}
__device__ bool columnFloor(StormCollision& terrain,vec3 feet) {
    auto above=StormCollision::block(feet+vec3(.0001f,.1001f,.0001f));
    auto below=StormCollision::block(feet+vec3(.0001f,-.1999f,.0001f));
    int upper=terrain.lookup(StormCollision::section(above)),lower=terrain.lookup(StormCollision::section(below));
    return upper!=-1&&lower!=-1&&!terrain.solid(above,upper)&&terrain.solid(below,lower);
}
__device__ void startColumnBloom(StormColumnMember& member,const StormColumn& column,vec3 position,float time) {
    auto relative=position-vec3(column.landingTop);
    member.touchdown=time;
    member.bloomRadius=clamp(sqrtf(relative.x*relative.x+relative.z*relative.z),0.f,4.f);
    member.bloomAngle=atan2f(relative.z,relative.x);
    member.distance=0.f;
    member.phase=columnBloom;
}

struct ColumnMotion {
    vec3 velocity{0.f},exitVelocity{0.f};
    vec3 bloomPoint{0.f};
    float marker{},speedCap{6.f};
    bool engaged{},exited{},bloomStarted{};
};
__device__ ColumnMotion advanceColumn(StormColumnMember& member,const StormColumn& column,unsigned rank,
        StormCollision& terrain,vec3 position,vec3 velocity,vec3 home,vec3 homeVelocity,float now,float step) {
    ColumnMotion result;
    if(now<0.f||now-step>=column.end||!column.state)return result;
    result.engaged=true;
    if(column.state==2u){result.marker=3.f;return result;}
    if(member.id!=column.id||member.identity!=column.identity){
        member={};
        member.id=column.id;member.identity=column.identity;
        member.touchdown=member.release=-1.f;
        member.origin=position.row(0.f);member.initialVelocity=velocity.row(0.f);
    }
    if(member.phase==columnDone){result.engaged=false;return result;}
    if(member.phase==0u){
        if(now<column.assemble){
            float duration=fmaxf(column.assemble,.1f),u=clamp(now/duration,0.f,1.f);
            auto target=blend(vec3(member.origin),columnGather(column,rank,now),columnSmooth(u))
                +vec3(member.initialVelocity)*(duration*u*(1.f-u)*(1.f-u)*(1.f-u));
            result.velocity=(target-position)*(1.f/step);
            result.marker=3.f;result.speedCap=8.f;
            return result;
        }
        float begin=now-step;
        auto direction=velocity;
        if(length(position-columnGather(column,rank,begin))<=.4f&&length(velocity-columnOrbitVelocity(column,rank,begin))<=1.f){
            auto radial=unit(vec3(position.x-column.landingTop.x,0.f,position.z-column.landingTop.z),vec3(0.f));
            if(length(radial)>.5f)direction=vec3(-radial.z*columnOrbitSpeed,-sqrtf(27.f),radial.x*columnOrbitSpeed);
        }
        initColumnCurve(member,column,rank,position,direction,begin);
    }
    float remaining=columnSpeed*step;
    vec3 point=position,motion(0.f);
    if(member.phase==columnDescent&&columnFloor(terrain,position)&&fract(position.y)<.04f){
        startColumnBloom(member,column,position,now-step);
        result.bloomStarted=true;result.bloomPoint=position;
    }
    if(member.phase==columnDescent){
        float total=member.arc[64],move=fminf(remaining,fmaxf(0.f,total-member.distance));
        auto before=columnCurvePoint(member.curve,columnParameter(member,member.distance));
        auto after=columnCurvePoint(member.curve,columnParameter(member,member.distance+move));
        auto direction=unit(after-before,vec3(0.f));
        motion=direction*move;point=position+motion;
        remaining-=move;member.distance+=move;
        if(length(direction)>.5f)member.tangent=direction.row(0.f);
        if(member.distance<total-.00001f){
            result.marker=4.f+clamp(member.distance/fmaxf(total,.00001f),0.f,.99999f);
            result.velocity=motion*(1.f/step);
            return result;
        }
        auto local=point-vec3(column.landingTop);
        if(columnFloor(terrain,point)&&local.x*local.x+local.z*local.z<=16.f){
            startColumnBloom(member,column,point,now-remaining/columnSpeed);
            result.bloomStarted=true;result.bloomPoint=point;
        }else{
            member.phase=columnExit;
            member.release=now-remaining/columnSpeed;
        }
    }
    if(member.phase==columnBloom){
        float start=columnArc(member.bloomRadius),total=columnArc(4.f)-start;
        float move=fminf(remaining,fmaxf(0.f,total-member.distance));
        float r=columnRadius(start+member.distance),next=columnRadius(start+member.distance+move);
        float c=cosf(member.bloomAngle),s=sinf(member.bloomAngle);
        motion=motion+vec3(c*(next-r),columnHeight(next)-columnHeight(r),s*(next-r));point=position+motion;
        member.tangent=unit(vec3(c,next*.125f,s),vec3(0,0,1)).row(0.f);
        remaining-=move;member.distance+=move;
        if(member.distance<total-.00001f){
            result.marker=-1.f-clamp(member.distance/fmaxf(total,.00001f),0.f,1.f);
            result.velocity=motion*(1.f/step);
            return result;
        }
        member.phase=columnExit;
        member.release=now-remaining/columnSpeed;
    }
    if(member.phase==columnExit){
        float age=fmaxf(0.f,now-member.release);
        float available=fmaxf(0.f,.5f-fmaxf(0.f,now-step-member.release));
        float move=fminf(remaining,available*columnSpeed);
        auto direction=unit(vec3(member.tangent),vec3(0,0,1));
        if(length(home-point)>.00001f)direction=boundedTurn(direction,unit(home-point,direction),
            2.5f*fminf(step,.5f)*columnSmooth(age/.5f));
        member.tangent=direction.row(0.f);
        motion=motion+direction*move;point=position+motion;remaining-=move;
        result.marker=5.f;
        if(age>=.5f){
            float ordinary=remaining/columnSpeed;
            auto finalVelocity=direction*columnSpeed;
            if(ordinary>0.f){
                finalVelocity=finalVelocity+((home-point)*24.f+(homeVelocity-finalVelocity)*6.f)*ordinary;
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

__device__ float columnLocalClock(float absolute,float start) {
    float d=absolute-start;
    return d< -52428.8f?d+104857.6f:d>52428.8f?d-104857.6f:d;
}
__device__ void restoreColumn(StormColumnMember& member,const StormColumn& column,unsigned rank,
        const StormColumnRestore& snapshot,vec3& position,vec3& velocity) {
    auto row=reinterpret_cast<const float*>(&snapshot);
    auto base=vec3(column.landingTop);
    auto xyz=[row](unsigned index){return vec3(row[index],row[index+1],row[index+2]);};
    position=base+xyz(1);velocity=xyz(4);
    member={};member.id=column.id;member.identity=column.identity;
    member.phase=unsigned(row[0]);member.restoreToken=column.restoreToken;member.replaying=true;
    if(member.phase==columnDescent)initColumnCurve(member,column,rank,base+xyz(17),xyz(20),
        columnLocalClock(row[7],column.start));
    member.touchdown=row[8]<0.f?-1.f:columnLocalClock(row[8],column.start);
    member.release=row[9]<0.f?-1.f:columnLocalClock(row[9],column.start);
    member.distance=row[10];
    member.origin=(base+xyz(11)).row(0.f);member.initialVelocity=xyz(14).row(0.f);
    member.tangent=xyz(23).row(0.f);member.bloomRadius=row[26];member.bloomAngle=row[27];
    if(member.phase!=0u){
        member.descentStart=columnLocalClock(row[7],column.start);
        member.descentOrigin=(base+xyz(17)).row(0.f);member.descentDirection=xyz(20).row(0.f);
    }
    member.replayCursor=column.restoreElapsed;
}

__device__ void columnContact(StormColumnBatch& batch,const StormColumn& column,const StormColumnMember& state,
        StormTick tick,vec3 before,vec3 after,float now,float step,unsigned terrainState) {
    unsigned target=__float_as_uint(tick.targetAfter.w);
    if(target==0||state.touchdown<0.f||(terrainState&(StormCoverageUnknown|StormDiscontinuity|StormEmbedded))!=0u)return;
    if(atomicAdd(&batch.contact.target,0u)!=0u)return;
    float end=state.release>=0.f?fminf(column.end,state.release):column.end;
    float first=fmaxf(0.f,(state.touchdown-now+step)/step);
    float last=fminf(1.f,(end-now+step)/step);
    if(first>last||end<=state.touchdown||now-step>=end)return;
    auto a=before-vec3(column.landingTop),b=after-vec3(column.landingTop);
    if(!clipSphereRange(vec3(a.x,0.f,a.z),vec3(b.x,0.f,b.z),4.0001f,first,last)
        ||!clipSphereRange(before-vec3(tick.targetBefore),after-vec3(tick.targetAfter),contactRadius,first,last))return;
    float age=now+(first-1.f)*step;
    if(age>=end||atomicCAS(&batch.contact.target,0u,target)!=0u)return;
    float seconds=tick.seconds+(first-1.f)*step;
    batch.contact.pointTime=blend(before,after,first).row(seconds<0.f?seconds+104857.6f:seconds);
}
