static const float contactRadius=1.6f;

vec3 blend(vec3 a,vec3 b,float alpha) { return a+(b-a)*alpha; }
bool clipSphereRange(vec3 before,vec3 after,float radius,inout float first,inout float last) {
    vec3 travel=after-before;
    float travelSquared=dot(travel,travel);
    if(travelSquared<=1e-12f){
        return dot(before,before)<=radius*radius;
    }
    float nearest=-dot(before,travel)/travelSquared;
    vec3 offset=before+travel*nearest;
    float margin=radius*radius-dot(offset,offset);
    if(margin<0.f)return false;
    float radiusInTime=sqrt(margin/travelSquared);
    first=max(first,nearest-radiusInTime);
    last=min(last,nearest+radiusInTime);
    return first<=last;
}
bool movingSphereContact(vec3 before,vec3 after,float first,float last,float radius,inout float at) {
    bool hit=clipSphereRange(before,after,radius,first,last);
    at=first;
    return hit;
}

vec3 attackPosition(StormDome attackData,StormHelix helixData,uint rank,vec3 local,bool helix,float age) {
    if(helix)return helixSample(attackData,helixData,unpackHelix(helixData.current),local,age).position;
    return domeWorldSample(attackData,rank,age).position;
}
vec3 contactPoint(StormDome attackData,StormHelix helixData,uint rank,vec3 local,bool helix,
        float elapsed,float dt,vec3 before,vec3 after,vec3 analyticBefore,vec3 analyticAfter,float alpha) {
    vec3 value=attackPosition(attackData,helixData,rank,local,helix,elapsed+(alpha-1.f)*dt);
    return blend(before,after,alpha)+(value-blend(analyticBefore,analyticAfter,alpha));
}

// The caller filters invalid targets and unverified terrain before building
// the path. Only the contact interval and endpoints cross this device call.
void stormContact(uint attackSlot,float4 targetBeforeRow,float4 targetAfterRow,
        float seconds,float lockedAt,float endAt,float elapsed,float dt,vec3 before,vec3 after,
        uint terrainState,float speedCap,StormDome attackData, StormHelix helixData, uint rank, vec3 local, bool helix) {
    uint target=asuint(targetAfterRow.w);
    if(batches.Load(domeContactOffset(attackSlot))!=0)return;
    float first=max(0.f,(lockedAt-elapsed+dt)/dt);
    float last=min(1.f,(endAt-elapsed+dt)/dt);
    if(first>last||last<0.f||first>1.f)return;
    vec3 targetBefore=vec3(targetBeforeRow),targetAfter=vec3(targetAfterRow);
    float alpha;
    vec3 position=0;
    if((terrainState&StormTerrainClipped)!=0){
        if(!movingSphereContact(before-targetBefore,after-targetAfter,first,last,contactRadius,alpha))return;
        position=blend(before,after,alpha);
    }else{
        // Reject distant candidates before sampling the corrected trajectory.
        // Dense coverage and continuous curvature bounds are separate work.
        if(!movingSphereContact(before-targetBefore,after-targetAfter,first,last,contactRadius+2.f*speedCap*dt,alpha))return;
        vec3 analyticBefore=attackPosition(attackData,helixData,rank,local,helix,elapsed-dt);
        vec3 analyticAfter=attackPosition(attackData,helixData,rank,local,helix,elapsed);
        float segmentStart=first;
        vec3 start=contactPoint(attackData,helixData,rank,local,helix,elapsed,dt,before,after,analyticBefore,analyticAfter,segmentStart);
        bool hit=false;
        for(uint i=1;i<=8&&!hit;i++){
            float segmentEnd=first+(last-first)*(float(i)*.125f);
            vec3 end=contactPoint(attackData,helixData,rank,local,helix,elapsed,dt,before,after,analyticBefore,analyticAfter,segmentEnd);
            vec3 relativeStart=start-blend(targetBefore,targetAfter,segmentStart);
            vec3 relativeEnd=end-blend(targetBefore,targetAfter,segmentEnd);
            vec3 travel=relativeEnd-relativeStart;
            float travelSquared=dot(travel,travel);
            float localAlpha=travelSquared>1e-12f?clamp(-dot(relativeStart,travel)/travelSquared,0.f,1.f):0.f;
            alpha=segmentStart+(segmentEnd-segmentStart)*localAlpha;
            position=contactPoint(attackData,helixData,rank,local,helix,elapsed,dt,before,after,analyticBefore,analyticAfter,alpha);
            vec3 offset=position-blend(targetBefore,targetAfter,alpha);
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
    uint oldTarget;
    batches.InterlockedCompareExchange(domeContactOffset(attackSlot),0u,target,oldTarget);
    if(oldTarget!=0u)return;
    float hitTime=seconds+(alpha-1.f)*dt;
    batches.Store4(domeContactOffset(attackSlot)+16u,asuint(float4(position,hitTime<0.f?hitTime+104857.6f:hitTime)));
}
