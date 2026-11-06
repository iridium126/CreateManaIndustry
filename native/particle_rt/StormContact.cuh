constexpr float contactRadius=1.6f;

__device__ vec3 blend(vec3 a,vec3 b,float alpha) { return a+(b-a)*alpha; }
__device__ bool clipSphereRange(vec3 before,vec3 after,float radius,float& first,float& last) {
    auto travel=after-before;
    float travelSquared=dot(travel,travel);
    if(travelSquared<=1e-12f){
        return dot(before,before)<=radius*radius;
    }
    float nearest=-dot(before,travel)/travelSquared;
    auto offset=before+travel*nearest;
    float margin=radius*radius-dot(offset,offset);
    if(margin<0.f)return false;
    float radiusInTime=sqrtf(margin/travelSquared);
    first=fmaxf(first,nearest-radiusInTime);
    last=fminf(last,nearest+radiusInTime);
    return first<=last;
}
__device__ bool movingSphereContact(vec3 before,vec3 after,float first,float last,float radius,float& at) {
    bool hit=clipSphereRange(before,after,radius,first,last);
    at=first;
    return hit;
}

struct DomeContactReference {
    StormDomeView view;
    const StormDome& attack;
    unsigned rank;
    __device__ vec3 point(float elapsed) const {
        return domeWorldSample(view,attack,rank,elapsed).point;
    }
};
struct HelixContactReference {
    const StormDome& attack;
    const StormHelix& helix;
    HelixReference reference;
    vec3 local;
    __device__ vec3 point(float elapsed) const {
        return helixSample(attack,helix,reference,local,elapsed).point;
    }
};
template<class Reference>
struct StormContactCurve {
    const Reference& reference;
    float elapsed,dt;
    vec3 before,after,analyticBefore,analyticAfter;

    __device__ vec3 point(float alpha) const {
        auto sample=reference.point(elapsed+(alpha-1.f)*dt);
        return blend(before,after,alpha)+(sample-blend(analyticBefore,analyticAfter,alpha));
    }
};

// The caller filters invalid targets and unverified terrain before building
// the path. Only the contact interval and endpoints cross this device call.
template<class Reference>
__device__ void stormContact(StormDomeHit& result,float4 targetBeforeRow,float4 targetAfterRow,
        float seconds,float lockedAt,float endAt,float elapsed,float dt,vec3 before,vec3 after,
        unsigned terrainState,float speedCap,const Reference& reference) {
    unsigned target=__float_as_uint(targetAfterRow.w);
    if(atomicAdd(&result.target,0u)!=0)return;
    float first=fmaxf(0.f,(lockedAt-elapsed+dt)/dt);
    float last=fminf(1.f,(endAt-elapsed+dt)/dt);
    if(first>last||last<0.f||first>1.f)return;
    auto targetBefore=vec3(targetBeforeRow),targetAfter=vec3(targetAfterRow);
    float alpha;
    vec3 position(0.f);
    if((terrainState&StormTerrainClipped)!=0){
        if(!movingSphereContact(before-targetBefore,after-targetAfter,first,last,contactRadius,alpha))return;
        position=blend(before,after,alpha);
    }else{
        // Reject distant candidates before sampling the corrected trajectory.
        // Dense coverage and continuous curvature bounds are separate work.
        if(!movingSphereContact(before-targetBefore,after-targetAfter,first,last,contactRadius+2.f*speedCap*dt,alpha))return;
        StormContactCurve<Reference> curve{reference,elapsed,dt,before,after,
            reference.point(elapsed-dt),reference.point(elapsed)};
        float segmentStart=first;
        auto start=curve.point(segmentStart);
        bool hit=false;
        for(unsigned i=1;i<=8&&!hit;i++){
            float segmentEnd=first+(last-first)*(float(i)*.125f);
            auto end=curve.point(segmentEnd);
            auto relativeStart=start-blend(targetBefore,targetAfter,segmentStart);
            auto relativeEnd=end-blend(targetBefore,targetAfter,segmentEnd);
            auto travel=relativeEnd-relativeStart;
            float travelSquared=dot(travel,travel);
            float localAlpha=travelSquared>1e-12f?clamp(-dot(relativeStart,travel)/travelSquared,0.f,1.f):0.f;
            alpha=segmentStart+(segmentEnd-segmentStart)*localAlpha;
            position=curve.point(alpha);
            auto offset=position-blend(targetBefore,targetAfter,alpha);
            hit=dot(offset,offset)<=contactRadius*contactRadius;
            if(!hit&&dot(relativeEnd,relativeEnd)<=contactRadius*contactRadius){
                alpha=segmentEnd;position=end;hit=true;
            }
            segmentStart=segmentEnd;start=end;
        }
        if(!hit)return;
    }
    float age=elapsed+(alpha-1.f)*dt;
    if(age<lockedAt||age>=endAt)return;
    if(atomicCAS(&result.target,0u,target)!=0u)return;
    float hitTime=seconds+(alpha-1.f)*dt;
    result.pointTime=position.row(hitTime<0.f?hitTime+104857.6f:hitTime);
}
