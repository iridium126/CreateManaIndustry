#pragma cmi_include packages/sweep.glsl
struct WorldSection { ivec4 keySlot; uvec4 revisionActive; };
layout(std430,binding=4) readonly buffer WorldSections { WorldSection worldSections[]; };
layout(std430,binding=5) readonly buffer WorldData { uint worldData[]; };
uniform bool uWorldReady;
uniform ivec3 uWorldOriginSection;
uniform uint uWorldTableMask,uWorldSlotWords,uWorldShapeCapacity;
uniform float uDt;
uint worldFailure=FREEZE_WORLD_BOUNDS;
ivec4 worldFailureDetail=ivec4(0);
uvec2 worldFailureRevision=uvec2(0),worldLookupRevision=uvec2(0);
uint worldFailureWait=0u,worldLookupWait=0u;
bool worldFailureAt(uint reason,ivec4 detail){worldFailure=reason;worldFailureDetail=detail;worldFailureRevision=uvec2(0);worldFailureWait=0u;return false;}
void freezeForWorldCollision(inout Body body,vec3 position,uint stage){
    freezePackage(body,position,worldFailure,stage,worldFailureDetail);
    if(worldFailure==FREEZE_SECTION_VERSION)recordWorldFreezeVersion(worldFailureRevision,worldFailureWait);
}

int worldSlot(ivec3 key,out bool clear) {
    clear=false;
    if(!uWorldReady)return -1;
    uvec3 c=uvec3(key);uint row=((c.x*73856093u)^(c.y*19349663u)^(c.z*83492791u))&uWorldTableMask;
    for(uint probe=0u;probe<=uWorldTableMask;probe++) {
        WorldSection section=worldSections[row];
        if(section.keySlot.w==-1)return -1;
        if(all(equal(section.keySlot.xyz,key))){
            if(section.keySlot.w==-2){worldLookupRevision=section.revisionActive.xy;worldLookupWait=section.revisionActive.w;return -3;}
            clear=section.revisionActive.w==1u;return section.revisionActive.z==1u?section.keySlot.w:-1;
        }
        row=(row+1u)&uWorldTableMask;
    }
    return -1;
}
int worldSlot(ivec3 key){bool clear;return worldSlot(key,clear);}
// Exact immutable metadata, rather than an assumption about an uncaptured section.
bool clearWorld(ivec3 first,ivec3 last) {
    ivec3 lo=(first>>4)+uWorldOriginSection,hi=(last>>4)+uWorldOriginSection;
    for(int z=lo.z;z<=hi.z;z++)for(int y=lo.y;y<=hi.y;y++)for(int x=lo.x;x<=hi.x;x++) {
        bool clear;if(worldSlot(ivec3(x,y,z),clear)<0 || !clear)return false;
    }
    return true;
}
bool worldBounds(vec3 lo,vec3 hi,out ivec3 first,out ivec3 last) {
    if(!uWorldReady)return worldFailureAt(FREEZE_WORLD_NOT_READY,ivec4(uWorldOriginSection,0));
    if(any(isnan(lo)) || any(isinf(lo)) || any(isnan(hi)) || any(isinf(hi))
            || any(greaterThan(abs(lo),vec3(1000000))) || any(greaterThan(abs(hi),vec3(1000000))))
        return worldFailureAt(FREEZE_WORLD_BOUNDS,ivec4(0,0,0,1));
    // Every uploaded local shape stays within [-1,2]. The one-cell guard includes overhangs.
    first=ivec3(floor(lo-1.0));last=ivec3(floor(hi+1.0));ivec3 size=last-first+1;
    bool fits=all(greaterThan(size,ivec3(0))) && all(lessThanEqual(size,ivec3(16))) && size.x*size.y*size.z<=2048;
    return fits?true:worldFailureAt(FREEZE_WORLD_BOUNDS,ivec4(size,2));
}
bool worldCell(ivec3 block,vec3 lo,vec3 hi,inout ivec3 cached,inout int slot,out uvec4 cell,out uint base) {
    ivec3 section=(block>>4)+uWorldOriginSection;
    if(slot==-2 || any(notEqual(section,cached))){cached=section;slot=worldSlot(section);}
    if(slot<0){
        if(slot==-3){worldFailureAt(FREEZE_SECTION_VERSION,ivec4(section,int(worldLookupRevision.y)));worldFailureRevision=worldLookupRevision;worldFailureWait=worldLookupWait;return false;}
        return worldFailureAt(FREEZE_SECTION_MISSING,ivec4(section,0));
    }
    uint index=uint((block.x&15)|((block.z&15)<<4)|((block.y&15)<<8));
    base=uint(slot)*uWorldSlotWords;
    uint p=base+index*4u;cell=uvec4(worldData[p],worldData[p+1u],worldData[p+2u],worldData[p+3u]);
    ivec3 absoluteBlock=block+(uWorldOriginSection<<4);
    if(cell.y>uWorldShapeCapacity || cell.x>uWorldShapeCapacity-cell.y)return worldFailureAt(FREEZE_WORLD_METADATA,ivec4(absoluteBlock,1));
    float friction=uintBitsToFloat(cell.z);
    if(isnan(friction) || isinf(friction) || friction<0.0 || (cell.w&~0xff3fu)!=0u)return worldFailureAt(FREEZE_WORLD_METADATA,ivec4(absoluteBlock,2));
    // Unsupported contexts include overhanging/dynamic shapes. Unsupported cells pause only on actual overlap; fluid/fire are environment inputs.
    bool supported=(cell.w&8u)==0u || any(lessThanEqual(hi,vec3(block))) || any(greaterThanEqual(lo,vec3(block)+1.0));
    return supported?true:worldFailureAt(FREEZE_WORLD_UNSUPPORTED,ivec4(absoluteBlock,int(cell.w)));
}
void worldBox(uint base,uint shape,ivec3 block,out vec3 lo,out vec3 hi) {
    uint p=base+16384u+shape*8u;
    lo=vec3(block)+vec3(uintBitsToFloat(worldData[p]),uintBitsToFloat(worldData[p+1u]),uintBitsToFloat(worldData[p+2u]));
    hi=vec3(block)+vec3(uintBitsToFloat(worldData[p+4u]),uintBitsToFloat(worldData[p+5u]),uintBitsToFloat(worldData[p+6u]));
}
// Pick the largest conservative time slice that stays inside the bounded voxel
// query. The server accepts up to 2048 blocks/second; at the 20 Hz physics step
// that is 102.4 blocks. Subdividing only the exceptional long path keeps the
// common one-query sweep unchanged while bounding every GPU voxel walk.
bool worldSweepBoundsFit(vec3 motion,vec3 extent) {
    ivec3 size=ivec3(ceil(abs(motion)+2.0*extent+vec3(2.0)))+1;
    return all(lessThanEqual(size,ivec3(16))) && size.x*size.y*size.z<=2048;
}
int worldSweepSegments(vec3 motion,vec3 extent) {
    if(worldSweepBoundsFit(motion,extent))return 1;
    float low=0.0,high=1.0;
    for(int iteration=0;iteration<16;iteration++) {
        float fraction=(low+high)*.5;
        if(worldSweepBoundsFit(motion*fraction,extent))low=fraction;else high=fraction;
    }
    int segments=max(2,int(ceil(1.0/max(low,1e-5))));
    // Binary-search rounding can add one unnecessary slice at exact integer
    // boundaries. Verify and trim it with the same conservative bound.
    if(segments>2 && worldSweepBoundsFit(motion/float(segments-1),extent))segments--;
    // Admitted package speed and the fixed <=.05 s physics step need fewer than
    // 64 slices. Keep an explicit shader bound for malformed/non-finite state.
    return clamp(segments,1,64);
}
bool sweepWorldSegment(Body b,vec3 p,vec3 motion,inout float closest,inout vec3 normal,out bool hitFound) {
    hitFound=false;
    vec3 lo=min(p,p+motion)-b.extentYaw.xyz,hi=max(p,p+motion)+b.extentYaw.xyz;
    ivec3 first,last,cached=ivec3(0);int slot=-2;
    if(!worldBounds(lo,hi,first,last))return false;
    if(clearWorld(first,last))return true;
    for(int z=first.z;z<=last.z;z++)for(int y=first.y;y<=last.y;y++)for(int x=first.x;x<=last.x;x++) {
        ivec3 block=ivec3(x,y,z);uvec4 cell;uint base;
        if(!worldCell(block,lo,hi,cached,slot,cell,base))return false;
        for(uint j=0u;j<cell.y;j++) {
            vec3 boxLo,boxHi;worldBox(base,cell.x+j,block,boxLo,boxHi);
            if(any(lessThanEqual(boxHi,boxLo)))continue;
            float time;vec3 boxNormal;
            if(sweepBox(p,motion,boxLo-b.extentYaw.xyz,boxHi+b.extentYaw.xyz,time,boxNormal) && time<=closest){closest=time;normal=boxNormal;hitFound=true;}
        }
    }
    return true;
}
bool sweepWorld(Body b,vec3 p,vec3 motion,inout float closest,inout vec3 normal) {
    float limit=clamp(closest,0.0,1.0);vec3 boundedMotion=motion*limit;
    int segments=worldSweepSegments(boundedMotion,b.extentYaw.xyz);
    for(int segment=0;segment<segments;segment++) {
        float begin=float(segment)/float(segments),end=float(segment+1)/float(segments);
        float localClosest=1.0;vec3 localNormal=vec3(0);bool hit;
        if(!sweepWorldSegment(b,p+boundedMotion*begin,boundedMotion*(end-begin),localClosest,localNormal,hit))return false;
        if(hit) {closest=limit*mix(begin,end,localClosest);normal=localNormal;return true;}
    }
    return true;
}
// A collision-frozen body must not be resumed until its next swept range is
// available. Check cells and unsupported markers without repeating the shape
// sweep; this also prevents a one-step unfreeze/refreeze pulse at section seams.
bool worldAvailable(vec3 lo,vec3 hi) {
    ivec3 first,last,cached=ivec3(0);int slot=-2;
    if(!worldBounds(lo,hi,first,last))return false;
    if(clearWorld(first,last))return true;
    for(int z=first.z;z<=last.z;z++)for(int y=first.y;y<=last.y;y++)for(int x=first.x;x<=last.x;x++) {
        uvec4 cell;uint base;
        if(!worldCell(ivec3(x,y,z),lo,hi,cached,slot,cell,base))return false;
    }
    return true;
}
bool worldSweepAvailable(Body b,vec3 p,vec3 motion) {
    int segments=worldSweepSegments(motion,b.extentYaw.xyz);
    for(int segment=0;segment<segments;segment++) {
        float begin=float(segment)/float(segments),end=float(segment+1)/float(segments);
        vec3 a=p+motion*begin,c=p+motion*end;
        if(!worldAvailable(min(a,c)-b.extentYaw.xyz,max(a,c)+b.extentYaw.xyz))return false;
    }
    return true;
}
// Jacobi can push beyond the predicted bounds. Validate the final contact correction before
// publishing it, including neighbour geometry; unknown sections must never become air.
bool coveredWorld(Body b) {
    vec3 lo=b.positionMass.xyz-b.extentYaw.xyz,hi=b.positionMass.xyz+b.extentYaw.xyz;
    return worldAvailable(lo,hi);
}
// Verify a depenetration endpoint against actual shapes, not just section coverage.
bool clearWorldPosition(Body b) {
    vec3 lo=b.positionMass.xyz-b.extentYaw.xyz,hi=b.positionMass.xyz+b.extentYaw.xyz;
    ivec3 first,last,cached=ivec3(0);int slot=-2;
    if(!worldBounds(lo,hi,first,last))return false;
    if(clearWorld(first,last))return true;
    for(int z=first.z;z<=last.z;z++)for(int y=first.y;y<=last.y;y++)for(int x=first.x;x<=last.x;x++) {
        ivec3 block=ivec3(x,y,z);uvec4 cell;uint base;
        if(!worldCell(block,lo,hi,cached,slot,cell,base))return false;
        for(uint j=0u;j<cell.y;j++) {
            vec3 a,c;worldBox(base,cell.x+j,block,a,c);
            if(any(lessThanEqual(c,a)))continue;
            if(all(greaterThan(min(hi,c)-max(lo,a),vec3(1e-5))))return false;
        }
    }
    return true;
}
// Only choose lateral package separation when both known world surfaces leave
// less vertical room than the contacting pair requires. Ordinary floor-supported
// stacks with free headroom retain their original vertical contact constraints.
bool insufficientWorldHeight(Body b,float required) {
    if(required<=0.||!clearWorldPosition(b))return false;
    float up=1.,down=1.;vec3 upNormal=vec3(0),downNormal=vec3(0);
    if(!sweepWorld(b,b.positionMass.xyz,vec3(0,required,0),up,upNormal)
            ||!sweepWorld(b,b.positionMass.xyz,vec3(0,-required,0),down,downNormal))return false;
    return upNormal.y<-.5&&downNormal.y>.5
            &&2.*b.extentYaw.y+required*(up+down)<required-1e-4;
}
// Adjacent solid voxels may choose opposite MTVs at their shared interior face.
// Try the six exterior directions only for that exceptional conflict. Each short
// candidate needs both a safe sweep (no intervening obstacle) and a clear endpoint.
bool resolveOpposingWorld(Body b,ivec3 first,ivec3 last,out vec3 correction,
        inout vec3 velocity,inout bool grounded,out float friction) {
    vec3 lo=b.positionMass.xyz-b.extentYaw.xyz,hi=b.positionMass.xyz+b.extentYaw.xyz;
    vec3 positive=vec3(0),negative=vec3(0);ivec3 cached=ivec3(0);int slot=-2;float upFriction=.6;
    correction=vec3(0);friction=-1.;
    for(int z=first.z;z<=last.z;z++)for(int y=first.y;y<=last.y;y++)for(int x=first.x;x<=last.x;x++) {
        ivec3 block=ivec3(x,y,z);uvec4 cell;uint base;
        if(!worldCell(block,lo,hi,cached,slot,cell,base))return false;
        for(uint j=0u;j<cell.y;j++) {
            vec3 a,c;worldBox(base,cell.x+j,block,a,c);
            if(any(lessThanEqual(c,a))||!all(greaterThan(min(hi,c)-max(lo,a),vec3(0))))continue;
            vec3 push=c-lo,pull=a-hi;
            if(push.y>positive.y)upFriction=uintBitsToFloat(cell.z);
            positive=max(positive,push);negative=min(negative,pull);
        }
    }
    float best=4.0001;int selected=-1;float direction=0.;
    for(int candidate=0;candidate<6;candidate++) {
        int axis=candidate/2;float sign=candidate%2==0?1.:-1.;
        float shift=(sign>0.?positive[axis]:negative[axis])+sign*1e-4;
        float distance=abs(shift);if(distance<=1e-4||distance>=best)continue;
        vec3 motion=vec3(0);motion[axis]=shift;float fraction=1.;vec3 normal=vec3(0);
        if(!sweepWorld(b,b.positionMass.xyz,motion,fraction,normal)||fraction<1.-1e-6)continue;
        Body target=b;target.positionMass.xyz+=motion;if(!clearWorldPosition(target))continue;
        best=distance;selected=axis;direction=sign;correction=motion;
    }
    if(selected<0)return false;
    if(velocity[selected]*direction<0.)velocity[selected]=0.;
    if(selected==1&&direction>0.){grounded=true;friction=upFriction;}
    return true;
}
bool solveWorld(Body b,out vec3 correction,inout vec3 velocity,inout bool grounded,out float friction) {
    vec3 lo=b.positionMass.xyz-b.extentYaw.xyz,hi=b.positionMass.xyz+b.extentYaw.xyz;
    ivec3 first,last,cached=ivec3(0);int slot=-2;friction=-1.0;correction=vec3(0);
    vec3 positive=vec3(0),negative=vec3(0);
    vec3 originalVelocity=velocity;bool originalGrounded=grounded;
    if(!worldBounds(lo,hi,first,last))return false;
    if(clearWorld(first,last))return true;
    for(int z=first.z;z<=last.z;z++)for(int y=first.y;y<=last.y;y++)for(int x=first.x;x<=last.x;x++) {
        ivec3 block=ivec3(x,y,z);uvec4 cell;uint base;
        if(!worldCell(block,lo,hi,cached,slot,cell,base))return false;
        for(uint j=0u;j<cell.y;j++) {
            vec3 boxLo,boxHi;worldBox(base,cell.x+j,block,boxLo,boxHi);
            if(any(lessThanEqual(boxHi,boxLo)))continue;
            vec3 centre=(boxLo+boxHi)*.5,delta=b.positionMass.xyz-centre;
            vec3 overlap=b.extentYaw.xyz+(boxHi-boxLo)*.5-abs(delta);
            if(overlap.x>0.0 && overlap.z>0.0 && abs(lo.y-boxHi.y)<1e-4) {
                float material=uintBitsToFloat(cell.z);friction=friction<0.0?material:min(friction,material);grounded=true;
            }
            if(!all(greaterThan(overlap,vec3(0))))continue;
            int axis=overlap.y<=overlap.x && overlap.y<=overlap.z?1:(overlap.x<=overlap.z?0:2);
            float direction=delta[axis]>=0.0?1.0:-1.0;
            // A large dynamic correction can cross a voxel's centre. Resolve from the
            // previously separated face; per-voxel MTV alone can invent two lateral walls
            // inside a continuous floor or push the body through its underside.
            vec3 previousLo=b.previousSleep.xyz-b.extentYaw.xyz,previousHi=b.previousSleep.xyz+b.extentYaw.xyz;
            float best=1e30;int entryAxis=-1;float entryDirection=0;
            for(int a=0;a<3;a++) {
                if(previousLo[a]>=boxHi[a]-1e-4) {
                    float shift=boxHi[a]-lo[a];
                    if(shift>0 && shift<best){best=shift;entryAxis=a;entryDirection=1;}
                } else if(previousHi[a]<=boxLo[a]+1e-4) {
                    float shift=hi[a]-boxLo[a];
                    if(shift>0 && shift<best){best=shift;entryAxis=a;entryDirection=-1;}
                }
            }
            float depth=overlap[axis];
            if(entryAxis>=0){axis=entryAxis;direction=entryDirection;depth=best;}
            // World surfaces are rigid constraints. Adjacent voxel boxes cannot dilute
            // support by adding repeated contacts to the dynamic Jacobi average.
            if(direction>0.0)positive[axis]=max(positive[axis],depth);
            else negative[axis]=min(negative[axis],-depth);
            if(velocity[axis]*direction<0.0)velocity[axis]=0.0;
            if(axis==1 && direction>0.0) {
                grounded=true;float material=uintBitsToFloat(cell.z);friction=friction<0.0?material:min(friction,material);
            }
        }
    }
    // Two opposing surfaces narrower than the body need an admission/fallback adapter.
    if(any(bvec3(positive.x>0.0 && negative.x<0.0,positive.y>0.0 && negative.y<0.0,positive.z>0.0 && negative.z<0.0))) {
        velocity=originalVelocity;grounded=originalGrounded;
        if(resolveOpposingWorld(b,first,last,correction,velocity,grounded,friction))return true;
        return worldFailureAt(FREEZE_WORLD_OPPOSING,ivec4(positive.x>0.0&&negative.x<0.0?1:0,positive.y>0.0&&negative.y<0.0?1:0,positive.z>0.0&&negative.z<0.0?1:0,0));
    }
    correction=positive+negative;
    return true;
}
