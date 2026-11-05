struct DomeSample {
    vec3 point{0.f},velocity{0.f},acceleration{0.f};
    float age{};
    unsigned mappedRank{};
};

__device__ float domeNode(StormDomeView view,unsigned base,unsigned node,unsigned component) {
    return __uint_as_float(view.keys[(base+node)*CMI_RESOURCE_STORM_DOME_NODE_WORDS+component]);
}
__device__ vec3 domeVector(StormDomeView view,unsigned base,unsigned node,unsigned component) {
    return vec3(domeNode(view,base,node,component),domeNode(view,base,node,component+1),domeNode(view,base,node,component+2));
}
__device__ const float* domeRecord(StormDomeView view,unsigned rank) {
    return view.records+rank*CMI_RESOURCE_STORM_DOME_RECORD_WORDS;
}
__device__ DomeSample domeArc(StormDomeView view,unsigned base,unsigned count,float distance) {
    unsigned lo=0,hi=count-1;
    while(hi-lo>1){
        unsigned mid=(lo+hi)/2;
        if(domeNode(view,base,mid,3)<distance)lo=mid;else hi=mid;
    }
    float first=domeNode(view,base,lo,3);
    float span=domeNode(view,base,hi,3)-first;
    float u=clamp((distance-first)/fmaxf(span,.000001f),0.f,1.f);
    auto p=domeVector(view,base,lo,0);
    auto d=domeVector(view,base,hi,0)-p;
    auto v=domeVector(view,base,lo,4)*span,w=domeVector(view,base,hi,4)*span;
    auto a=domeVector(view,base,lo,8)*(span*span),b=domeVector(view,base,hi,8)*(span*span);
    auto c3=d*10.f-v*6.f-w*4.f-a*1.5f+b*.5f;
    auto c4=d*(-15.f)+v*8.f+w*7.f+a*1.5f-b;
    auto c5=d*6.f-v*3.f-w*3.f-a*.5f+b*.5f;
    DomeSample sample;
    sample.point=(((((c5*u+c4)*u+c3)*u+a*.5f)*u+v)*u+p);
    sample.velocity=((((c5*(5.f*u)+c4*4.f)*u+c3*3.f)*u+a)*u+v)*(1.f/span);
    sample.acceleration=(((c5*(20.f*u)+c4*12.f)*u+c3*6.f)*u+a)*(1.f/(span*span));
    return sample;
}
__device__ DomeSample domeGatherSample(StormDomeView view,unsigned rank,float elapsed,float pause) {
    auto record=domeRecord(view,rank);
    float phase=(float(rank)-record[3])/record[7]+record[9]*fminf(elapsed,pause)/record[10];
    auto sample=domeArc(view,unsigned(record[8]),unsigned(record[12]),fract(phase)*record[10]);
    float speed=elapsed>=pause?0.f:record[9];
    sample.velocity=sample.velocity*speed;
    sample.acceleration=sample.acceleration*(speed*speed);
    return sample;
}
__device__ float quintic(float u){return u*u*u*(10.f-15.f*u+6.f*u*u);}
__device__ float joinMotion(float u){return u-u*u*u*(6.f-8.f*u+3.f*u*u);}
__device__ float joinDerivative(float u){return 1.f-u*u*(18.f-32.f*u+15.f*u*u);}
__device__ float quinticDerivative(float u){return 30.f*u*u*(1.f-u)*(1.f-u);}
__device__ DomeSample domeLocalSample(StormDomeView view,const StormDome& attack,unsigned rank,float elapsed) {
    float pause=attack.paused[view.flows[rank]];
    if(attack.state!=2||elapsed<attack.lockedAt)return domeGatherSample(view,rank,elapsed,pause);
    auto record=domeRecord(view,rank);
    int base=int(record[3]),count=int(record[7]);
    int shift=int(floorf(record[9]*fminf(attack.lockedAt,pause)/record[10]*record[7]+.5f));
    int reduced=(int(rank)-base+shift)%count;
    unsigned mapped=unsigned(base+(reduced<0?reduced+count:reduced));
    auto flow=domeRecord(view,mapped);
    float age=elapsed-attack.lockedAt,speed=fabsf(flow[9]),u=fminf(age/.5f,1.f);
    float u2=u*u,u3=u2*u,u4=u2*u2;
    float distance=speed*age+(6.f-speed)*.5f*u4*(u2-3.f*u+2.5f)+fmaxf(age-.5f,0.f)*(6.f-speed);
    auto sample=domeArc(view,unsigned(flow[0]),unsigned(flow[1]),fminf(distance,flow[11]));
    sample.mappedRank=mapped;
    sample.age=age;
    sample.velocity=sample.velocity*(speed+(6.f-speed)*quintic(u));
    constexpr float handoff=1.25f;
    u=clamp(age/handoff,0.f,1.f);
    if(u>=1.f)return sample;
    auto entry=domeGatherSample(view,rank,attack.lockedAt,pause);
    auto start=unsigned(flow[0]);
    auto dp=entry.point-domeVector(view,start,0,0);
    auto dv=entry.velocity-domeVector(view,start,0,4)*speed;
    auto da=entry.acceleration-domeVector(view,start,0,8)*(speed*speed);
    u2=u*u;u3=u2*u;u4=u2*u2;
    sample.point=sample.point+dp*(1.f-quintic(u))+dv*(handoff*joinMotion(u))
            +da*(handoff*handoff*.5f*u2*(1.f-3.f*u+3.f*u2-u3));
    sample.velocity=sample.velocity-dp*(quinticDerivative(u)/handoff)+dv*joinDerivative(u)
            +da*(handoff*.5f*(2.f*u-9.f*u2+12.f*u3-5.f*u4));
    return sample;
}
__device__ vec3 domeRotate(vec3 v,float c,float s){return vec3(c*v.x-s*v.z,v.y,s*v.x+c*v.z);}
__device__ DomeSample domeWorldSample(StormDomeView view,const StormDome& attack,unsigned rank,float elapsed) {
    auto sample=domeLocalSample(view,attack,rank,elapsed);
    float c=cosf(attack.centerYaw.w),s=sinf(attack.centerYaw.w);
    sample.point=domeRotate(sample.point,c,s)+vec3(attack.centerYaw)-vec3(0.f,.3f,0.f);
    sample.velocity=domeRotate(sample.velocity,c,s);
    return sample;
}
__device__ float domeElapsed(StormTick tick,float start) {
    // The wrapped world clock is sampled on 50 ms boundaries. Its FP32
    // error is below half a tick throughout the wrap interval.
    double worldTick=floor(double(tick.seconds)*20.0+.5);
    double elapsed=worldTick*.05-double(start);
    if(elapsed< -52428.8)elapsed+=104857.6;
    else if(elapsed>52428.8)elapsed-=104857.6;
    return float(elapsed);
}
__device__ vec3 domeGatherDesired(StormDomeView view,const StormDome& attack,unsigned rank,float elapsed,
        float dt,vec3 pos,vec3 velocity,const DomeSample& sample) {
    auto previous=domeWorldSample(view,attack,rank,elapsed-dt);
    auto error=previous.point-pos;
    float distance=length(error);
    auto following=(sample.point-previous.point)*(1.f/dt)+error*fminf(6.f,.6f/fmaxf(distance,.000001f));
    if(distance<=1.f)return following;
    auto record=domeRecord(view,rank);
    auto center=vec3(attack.centerYaw)+vec3(0.f,record[5]-.3f,0.f);
    float lo=0.f,hi=(length(pos-center)+record[4]+.6f)/8.f+.1f;
    for(int i=0;i<10;i++){
        float mid=(lo+hi)*.5f;
        if(length(pos-domeWorldSample(view,attack,rank,elapsed+mid-dt).point)>8.f*mid)lo=mid;
        else hi=mid;
    }
    auto intercept=domeWorldSample(view,attack,rank,elapsed+fmaxf(hi,dt)-dt).point;
    auto difference=velocity-previous.velocity;
    float u=clamp((distance-1.f)/(fmaxf(2.f,1.f+dot(difference,difference)/36.f)-1.f),0.f,1.f);
    float blend=u*u*(3.f-2.f*u);
    return following*(1.f-blend)+(intercept-pos)*(blend/fmaxf(hi,dt));
}
__device__ vec3 domeAttackVelocity(StormDomeView view,const StormDome& attack,StormDomeMember& assignment,
        float elapsed,float dt,vec3 pos,vec3 velocity,const DomeSample& sample) {
    auto previous=domeWorldSample(view,attack,assignment.rank,elapsed-dt);
    if(assignment.phase!=DomeAttack){
        assignment.phase=DomeAttack;
        assignment.joinTime=elapsed-dt;
        assignment.joinOffset=(pos-previous.point).row(length(velocity));
        assignment.joinVelocity=(velocity-previous.velocity).row(0.f);
        assignment.blockedSeconds=0.f;
    }
    float age=clamp(elapsed-assignment.joinTime,0.f,1.f),priorAge=clamp(age-dt,0.f,1.f);
    auto offset=vec3(assignment.joinOffset),delta=vec3(assignment.joinVelocity);
    auto target=sample.point+offset*(1.f-quintic(age))+delta*joinMotion(age);
    auto before=previous.point+offset*(1.f-quintic(priorAge))+delta*joinMotion(priorAge);
    auto tangent=sample.velocity-offset*quinticDerivative(age)+delta*joinDerivative(age);
    auto error=before-pos;
    auto desired=(sample.age>=domeRecord(view,sample.mappedRank)[2]?tangent:(target-before)*(1.f/dt))
            +error*fminf(6.f,.6f/fmaxf(length(error),.000001f));
    float speed=assignment.joinOffset.w+(6.f-assignment.joinOffset.w)*quintic(fminf(age/.5f,1.f));
    return gatherVelocity(velocity,desired,speed,dt);
}
__device__ void domeComplete(StormDomeView view,StormDomeMember& member,StormRoute& route) {
    if(view.batch->attackIds[member.slot]==member.id)
        atomicOr(&view.batch->completed[member.slot][member.rank/32],1u<<(member.rank&31));
    member.phase=DomeReturn;
    member.streak=0;member.tracked=false;
    member.blockedSeconds=member.progressSeconds=0.f;
    route={};
}
__device__ bool domePastExit(StormDomeView view,const StormDome& attack,StormDomeMember& member,const DomeSample& sample,vec3 pos) {
    auto local=pos-vec3(attack.centerYaw);
    float c=cosf(attack.centerYaw.w),s=sinf(attack.centerYaw.w);
    local=domeRotate(local,c,-s)+vec3(0.f,.3f,0.f);
    auto record=domeRecord(view,sample.mappedRank);
    bool inside=dot(local,local)<=1024.f;
    if(!inside&&(member.inside||sample.age>=record[2]))return true;
    member.inside|=inside;
    unsigned base=unsigned(record[3]),count=unsigned(record[7]);
    bool core=(base/12+(sample.mappedRank-base)%(count/12))%16==0;
    member.entered|=length(local)<(core?2.f:8.5f);
    return member.entered&&dot(local,vec3(record[13],record[14],record[15]))>=record[6];
}
