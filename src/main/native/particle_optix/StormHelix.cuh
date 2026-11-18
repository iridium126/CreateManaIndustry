struct HelixReference {
    vec3 center{0.f}, velocity{0.f}, omega{0.f};
    float4 rotation{0.f,0.f,0.f,1.f};
    unsigned version{};
    float seconds{};
    bool valid{};
    __device__ HelixReference() {}
    __device__ explicit HelixReference(const StormHelixSample& sample)
        : center(sample.center),velocity(sample.velocity),omega(sample.omega),rotation(sample.rotation),
          version(sample.version),seconds(sample.seconds),valid(sample.valid!=0) {}
    __device__ StormHelixSample pack() const {
        return {center.row(0.f),velocity.row(0.f),omega.row(0.f),rotation,version,valid?1u:0u,seconds};
    }
};

__device__ float4 rotationProduct(float4 a,float4 b) {
    vec3 av(a),bv(b);
    return (bv*a.w+av*b.w+cross(av,bv)).row(a.w*b.w-dot(av,bv));
}
__device__ float4 rotationUnit(float4 q) {
    vec3 v(q);
    float norm=dot(v,v)+q.w*q.w;
    if(norm<1e-12f)return make_float4(0.f,0.f,0.f,1.f);
    float scale=rsqrtf(norm);
    return (v*scale).row(q.w*scale);
}
__device__ float4 rotationExp(vec3 v) {
    float a=length(v);
    return (v*(a<.00001f?.5f:sinf(a*.5f)/a)).row(cosf(a*.5f));
}
__device__ vec3 rotateHelix(float4 q,vec3 v) {
    vec3 axis(q);
    return v+cross(axis,cross(axis,v)+v*q.w)*2.f;
}
__device__ vec3 rotationJacobian(vec3 r,vec3 derivative) {
    float a=length(r);
    if(a<=.00001f)return derivative;
    auto first=cross(r,derivative);
    return derivative+first*((1.f-cosf(a))/(a*a))+cross(r,first)*((a-sinf(a))/(a*a*a));
}
__device__ HelixReference helixReference(const StormDome& attack,const StormHelix& helix,float elapsed) {
    HelixReference result;
    if(!helix.count)return result;
    float seconds=attack.state==2u?helix.freeze:fmaxf(0.f,elapsed-.25f);
    unsigned selected=0;
    for(unsigned i=1;i<helix.count;i++){
        if(helix.frames[i][0].y>seconds)break;
        selected=i;
    }
    const auto& frame=helix.frames[selected];
    float t=clamp(seconds-frame[0].y,0.f,.25f),u=t*4.f;
    auto v=vec3(frame[2]),nextV=vec3(frame[3]),w=vec3(frame[5]),dw=vec3(frame[6])-w;
    auto displacement=vec3(frame[7]);
    float u2=u*u,u3=u2*u;
    result.center=vec3(frame[1])+v*(.25f*(u3-2.f*u2+u))+displacement*(-2.f*u3+3.f*u2)+nextV*(.25f*(u3-u2));
    result.velocity=v*(3.f*u2-4.f*u+1.f)+displacement*((-6.f*u2+6.f*u)*4.f)+nextV*(3.f*u2-2.f*u);
    auto r=w*t+dw*(t*t/.5f);
    result.omega=rotationJacobian(r,w+dw*u);
    result.rotation=rotationUnit(rotationProduct(rotationExp(r),frame[4]));
    float extra=clamp(seconds-frame[0].y-.25f,0.f,.25f);
    if(extra>0.f){
        float travel=extra-extra*extra/.5f;
        result.center=result.center+result.velocity*travel;
        result.rotation=rotationUnit(rotationProduct(rotationExp(result.omega*travel),result.rotation));
        result.velocity=result.velocity*(1.f-extra*4.f);
        result.omega=result.omega*(1.f-extra*4.f);
    }
    const auto& restore=helix.recovery;
    bool recovering=restore[0].w>.5f&&seconds<restore[0].x+.25f;
    if(recovering){
        float z=clamp((seconds-restore[0].x)*4.f,0.f,1.f),z2=z*z,z3=z2*z;
        float h=2.f*z3-3.f*z2+1.f,g=.25f*(z3-2.f*z2+z),dh=(6.f*z2-6.f*z)*4.f,dg=3.f*z2-4.f*z+1.f;
        auto offset=vec3(restore[1]),delta=vec3(restore[2]),angle=vec3(restore[3]),tangent=vec3(restore[4]);
        auto rotation=angle*h+tangent*g;
        auto q=rotationExp(rotation);
        result.center=result.center+offset*h+delta*g;
        result.velocity=result.velocity+offset*dh+delta*dg;
        result.rotation=rotationUnit(rotationProduct(q,result.rotation));
        result.omega=rotationJacobian(rotation,angle*dh+tangent*dg)+rotateHelix(q,result.omega);
    }
    result.valid=frame[0].w==1.f&&!recovering&&seconds>=frame[0].y-.001f&&seconds<=frame[0].y+.501f;
    result.version=unsigned(frame[0].x);
    result.seconds=seconds;
    return result;
}

struct HelixMemberSample {
    vec3 point{0.f},velocity{0.f},center{0.f},axis{1.f,0.f,0.f};
};
__device__ HelixMemberSample helixSample(const StormDome& attack,const StormHelix& helix,
        const HelixReference& reference,vec3 local,float elapsed) {
    HelixMemberSample result;
    result.center=reference.center;
    result.axis=rotateHelix(reference.rotation,vec3(1,0,0));
    if(attack.state!=2u||elapsed<attack.lockedAt){
        auto offset=rotateHelix(reference.rotation,local);
        result.point=reference.center+offset;
        result.velocity=reference.velocity+cross(reference.omega,offset);
        return result;
    }
    constexpr float pitch=6.28318530718f*.75f/64.f;
    float age=fmaxf(0.f,elapsed-attack.lockedAt),ramp=clamp(age/.15f,0.f,1.f);
    float advance=helix.speed*(age-.075f*(1.f-(1.f-ramp)*(1.f-ramp)));
    float angle=-pitch*advance,s=sinf(angle),c=cosf(angle);
    auto radial=rotateHelix(reference.rotation,vec3(0.f,c*local.y-s*local.z,s*local.y+c*local.z));
    result.point=reference.center+result.axis*(local.x+advance)+radial;
    float speed=helix.speed*ramp;
    result.velocity=result.axis*speed+cross(result.axis,radial)*(-pitch*speed);
    return result;
}
__device__ vec3 helixGatherDesired(vec3 position,vec3 velocity,const HelixMemberSample& current,
        const HelixMemberSample& previous) {
    auto error=previous.point-position;
    auto dv=velocity-previous.velocity;
    float distance=length(error),transition=fmaxf(2.f,1.f+dot(dv,dv)/36.f);
    float t=clamp((distance-1.f)/(transition-1.f),0.f,1.f);
    float mix=t*t*(3.f-2.f*t);
    auto following=error*fminf(6.f,.6f/fmaxf(distance,.000001f));
    return current.velocity+following*(1.f-mix)+error*(2.f*mix);
}
__device__ vec3 helixAttackVelocity(StormDomeMember& member,float elapsed,float dt,vec3 position,
        vec3 velocity,const HelixMemberSample& current,const HelixMemberSample& previous) {
    if(member.phase!=DomeAttack){
        member.phase=DomeAttack;
        member.joinTime=elapsed-dt;
        member.joinOffset=(position-previous.point).row(0.f);
        member.joinVelocity=(velocity-previous.velocity).row(0.f);
        member.helixBlocked={};
    }
    float u=clamp((elapsed-member.joinTime)*2.f,0.f,1.f);
    auto offset=vec3(member.joinOffset),delta=vec3(member.joinVelocity);
    auto target=current.point+offset*(1.f-quintic(u))+delta*(.5f*joinMotion(u));
    auto tangent=current.velocity-offset*(2.f*quinticDerivative(u))+delta*joinDerivative(u);
    return velocity+((target-position)*24.f+(tangent-velocity)*6.f)*dt;
}
__device__ bool helixBlocked(StormDomeMember& member,bool clipped,float dt,float expected,float actual) {
    auto& window=member.helixBlocked;
    if(window.x<=0.f&&!clipped)return false;
    window.x+=dt;
    window.y+=clipped?dt:0.f;
    window.z+=fmaxf(0.f,expected);
    window.w+=actual;
    if(window.x<.5f)return false;
    bool blocked=window.y>=.1f&&window.z>=.05f&&window.w<window.z*.1f;
    window={};
    return blocked;
}
