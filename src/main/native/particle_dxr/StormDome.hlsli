struct DomeSample {
    vec3 position,velocity,acceleration;
    float age;
    uint mappedRank;
};

float domeNode(uint base,uint node,uint component) {
    return asfloat(domeKeys[(base+node)*CMI_RESOURCE_STORM_DOME_NODE_WORDS+component]);
}
vec3 domeVector(uint base,uint node,uint component) {
    return vec3(domeNode(base,node,component),domeNode(base,node,component+1),domeNode(base,node,component+2));
}
uint domeRecord(uint rank) { return rank*CMI_RESOURCE_STORM_DOME_RECORD_WORDS; }
DomeSample domeArc(uint base,uint count,float distance) {
    uint lo=0,hi=count-1;
    while(hi-lo>1){
        uint mid=(lo+hi)/2;
        if(domeNode(base,mid,3)<distance)lo=mid;else hi=mid;
    }
    float first=domeNode(base,lo,3);
    float span=domeNode(base,hi,3)-first;
    float u=clamp((distance-first)/max(span,.000001f),0.f,1.f);
    vec3 p=domeVector(base,lo,0);
    vec3 d=domeVector(base,hi,0)-p;
    vec3 v=domeVector(base,lo,4)*span,w=domeVector(base,hi,4)*span;
    vec3 a=domeVector(base,lo,8)*(span*span),b=domeVector(base,hi,8)*(span*span);
    vec3 c3=d*10.f-v*6.f-w*4.f-a*1.5f+b*.5f;
    vec3 c4=d*(-15.f)+v*8.f+w*7.f+a*1.5f-b;
    vec3 c5=d*6.f-v*3.f-w*3.f-a*.5f+b*.5f;
    DomeSample value=(DomeSample)0;
    value.position=(((((c5*u+c4)*u+c3)*u+a*.5f)*u+v)*u+p);
    value.velocity=((((c5*(5.f*u)+c4*4.f)*u+c3*3.f)*u+a)*u+v)*(1.f/span);
    value.acceleration=(((c5*(20.f*u)+c4*12.f)*u+c3*6.f)*u+a)*(1.f/(span*span));
    return value;
}
DomeSample domeGatherSample(uint rank,float elapsed,float pause) {
    uint record=domeRecord(rank);
    float phase=(float(rank)-domeRecords[record+3])/domeRecords[record+7]+domeRecords[record+9]*min(elapsed,pause)/domeRecords[record+10];
    DomeSample value=domeArc(uint(domeRecords[record+8]),uint(domeRecords[record+12]),frac(phase)*domeRecords[record+10]);
    float speed=elapsed>=pause?0.f:domeRecords[record+9];
    value.velocity=value.velocity*speed;
    value.acceleration=value.acceleration*(speed*speed);
    return value;
}
float quintic(float u){return u*u*u*(10.f-15.f*u+6.f*u*u);}
float joinMotion(float u){return u-u*u*u*(6.f-8.f*u+3.f*u*u);}
float joinDerivative(float u){return 1.f-u*u*(18.f-32.f*u+15.f*u*u);}
float quinticDerivative(float u){return 30.f*u*u*(1.f-u)*(1.f-u);}
DomeSample domeLocalSample(StormDome attack,uint rank,float elapsed) {
    float pause=attack.paused[domeFlowsDevice[rank]];
    if(attack.state!=2||elapsed<attack.lockedAt)return domeGatherSample(rank,elapsed,pause);
    uint record=domeRecord(rank);
    int base=int(domeRecords[record+3]),count=int(domeRecords[record+7]);
    int shift=int(floor(domeRecords[record+9]*min(attack.lockedAt,pause)/domeRecords[record+10]*domeRecords[record+7]+.5f));
    int reduced=(int(rank)-base+shift)%count;
    uint mapped=uint(base+(reduced<0?reduced+count:reduced));
    uint flow=domeRecord(mapped);
    float age=elapsed-attack.lockedAt,speed=abs(domeRecords[flow+9]),u=min(age/.5f,1.f);
    float u2=u*u,u3=u2*u,u4=u2*u2;
    float distance=speed*age+(6.f-speed)*.5f*u4*(u2-3.f*u+2.5f)+max(age-.5f,0.f)*(6.f-speed);
    DomeSample value=domeArc(uint(domeRecords[flow+0]),uint(domeRecords[flow+1]),min(distance,domeRecords[flow+11]));
    value.mappedRank=mapped;
    value.age=age;
    value.velocity=value.velocity*(speed+(6.f-speed)*quintic(u));
    static const float handoff=1.25f;
    u=clamp(age/handoff,0.f,1.f);
    if(u>=1.f)return value;
    DomeSample entry=domeGatherSample(rank,attack.lockedAt,pause);
    uint start=uint(domeRecords[flow+0]);
    vec3 dp=entry.position-domeVector(start,0,0);
    vec3 dv=entry.velocity-domeVector(start,0,4)*speed;
    vec3 da=entry.acceleration-domeVector(start,0,8)*(speed*speed);
    u2=u*u;u3=u2*u;u4=u2*u2;
    value.position=value.position+dp*(1.f-quintic(u))+dv*(handoff*joinMotion(u))
            +da*(handoff*handoff*.5f*u2*(1.f-3.f*u+3.f*u2-u3));
    value.velocity=value.velocity-dp*(quinticDerivative(u)/handoff)+dv*joinDerivative(u)
            +da*(handoff*.5f*(2.f*u-9.f*u2+12.f*u3-5.f*u4));
    return value;
}
vec3 domeRotate(vec3 v,float c,float s){return vec3(c*v.x-s*v.z,v.y,s*v.x+c*v.z);}
DomeSample domeWorldSample(StormDome attack,uint rank,float elapsed) {
    DomeSample value=domeLocalSample(attack,rank,elapsed);
    float c=cos(attack.centerYaw.w),s=sin(attack.centerYaw.w);
    value.position=domeRotate(value.position,c,s)+vec3(attack.centerYaw)-vec3(0.f,.3f,0.f);
    value.velocity=domeRotate(value.velocity,c,s);
    return value;
}
float domeElapsed(StormTick tick,float start) {
    // The wrapped world clock is sampled on 50 ms boundaries. Its FP32
    // error is below half a tick throughout the wrap interval.
    float worldSeconds=floor(tick.seconds);
    float startSeconds=floor(start);
    float partialTick=floor((tick.seconds-worldSeconds)*20.f+.5f);
    float elapsed=(worldSeconds-startSeconds)+(partialTick*.05f-(start-startSeconds));
    if(elapsed< -52428.8)elapsed+=104857.6;
    else if(elapsed>52428.8)elapsed-=104857.6;
    return float(elapsed);
}
vec3 domeGatherDesired(StormDome attack,uint rank,float elapsed,
        float dt,vec3 pos,vec3 velocity,DomeSample value) {
    DomeSample previous=domeWorldSample(attack,rank,elapsed-dt);
    vec3 error=previous.position-pos;
    float distance=length(error);
    vec3 following=(value.position-previous.position)*(1.f/dt)+error*min(6.f,.6f/max(distance,.000001f));
    if(distance<=1.f)return following;
    uint record=domeRecord(rank);
    vec3 center=vec3(attack.centerYaw)+vec3(0.f,domeRecords[record+5]-.3f,0.f);
    float lo=0.f,hi=(length(pos-center)+domeRecords[record+4]+.6f)/8.f+.1f;
    for(int i=0;i<10;i++){
        float mid=(lo+hi)*.5f;
        if(length(pos-domeWorldSample(attack,rank,elapsed+mid-dt).position)>8.f*mid)lo=mid;
        else hi=mid;
    }
    vec3 intercept=domeWorldSample(attack,rank,elapsed+max(hi,dt)-dt).position;
    vec3 difference=velocity-previous.velocity;
    float u=clamp((distance-1.f)/(max(2.f,1.f+dot(difference,difference)/36.f)-1.f),0.f,1.f);
    float blend=u*u*(3.f-2.f*u);
    return following*(1.f-blend)+(intercept-pos)*(blend/max(hi,dt));
}
vec3 domeAttackVelocity(StormDome attack,inout StormDomeMember assignment,
        float elapsed,float dt,vec3 pos,vec3 velocity,DomeSample value) {
    DomeSample previous=domeWorldSample(attack,assignment.rank,elapsed-dt);
    if(assignment.phase!=DomeAttack){
        assignment.phase=DomeAttack;
        assignment.joinTime=elapsed-dt;
        assignment.joinOffset=float4(pos-previous.position,length(velocity));
        assignment.joinVelocity=float4(velocity-previous.velocity,0.f);
        assignment.blockedSeconds=0.f;
    }
    float age=clamp(elapsed-assignment.joinTime,0.f,1.f),priorAge=clamp(age-dt,0.f,1.f);
    vec3 offset=vec3(assignment.joinOffset),delta=vec3(assignment.joinVelocity);
    vec3 target=value.position+offset*(1.f-quintic(age))+delta*joinMotion(age);
    vec3 before=previous.position+offset*(1.f-quintic(priorAge))+delta*joinMotion(priorAge);
    vec3 tangent=value.velocity-offset*quinticDerivative(age)+delta*joinDerivative(age);
    vec3 error=before-pos;
    vec3 desired=(value.age>=domeRecords[domeRecord(value.mappedRank)+2]?tangent:(target-before)*(1.f/dt))
            +error*min(6.f,.6f/max(length(error),.000001f));
    float speed=assignment.joinOffset.w+(6.f-assignment.joinOffset.w)*quintic(min(age/.5f,1.f));
    return gatherVelocity(velocity,desired,speed,dt);
}
void domeComplete(inout StormDomeMember member,inout StormRoute route) {
    if(batches.Load(member.slot*4u)==member.id)
        batches.InterlockedOr(domeBitmapOffset(CMI_RESOURCE_STORM_DOME_COMPLETED_OFFSET,member.slot,member.rank/32),1u<<(member.rank&31));
    member.phase=DomeReturn;
    member.streak=0;member.tracked=false;
    member.blockedSeconds=member.progressSeconds=0.f;
    route=(StormRoute)0;
}
bool domePastExit(StormDome attack,inout StormDomeMember member,DomeSample value,vec3 pos) {
    vec3 local=pos-vec3(attack.centerYaw);
    float c=cos(attack.centerYaw.w),s=sin(attack.centerYaw.w);
    local=domeRotate(local,c,-s)+vec3(0.f,.3f,0.f);
    uint record=domeRecord(value.mappedRank);
    bool inside=dot(local,local)<=1024.f;
    if(!inside&&(member.inside||value.age>=domeRecords[record+2]))return true;
    member.inside|=inside;
    uint base=uint(domeRecords[record+3]),count=uint(domeRecords[record+7]);
    bool core=(base/12+(value.mappedRank-base)%(count/12))%16==0;
    member.entered|=length(local)<(core?2.f:8.5f);
    return member.entered&&dot(local,vec3(domeRecords[record+13],domeRecords[record+14],domeRecords[record+15]))>=domeRecords[record+6];
}
