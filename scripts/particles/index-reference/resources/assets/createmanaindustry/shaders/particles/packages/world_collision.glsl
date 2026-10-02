#pragma cmi_include packages/sweep.glsl
struct WorldSection { ivec4 keySlot; uvec4 revisionActive; };
layout(std430,binding=4) readonly buffer WorldSections { WorldSection worldSections[]; };
layout(std430,binding=5) readonly buffer WorldData { uint worldData[]; };
uniform bool uWorldReady;
uniform ivec3 uWorldOriginSection;
uniform uint uWorldTableMask,uWorldSlotWords,uWorldShapeCapacity;
uniform float uDt;

int worldSlot(ivec3 key,out bool clear) {
    clear=false;
    if(!uWorldReady)return -1;
    uvec3 c=uvec3(key);uint row=((c.x*73856093u)^(c.y*19349663u)^(c.z*83492791u))&uWorldTableMask;
    for(uint probe=0u;probe<=uWorldTableMask;probe++) {
        WorldSection section=worldSections[row];
        if(section.keySlot.w<0)return -1;
        if(all(equal(section.keySlot.xyz,key))){clear=section.revisionActive.w==1u;return section.revisionActive.z==1u?section.keySlot.w:-1;}
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
    if(!uWorldReady || any(isnan(lo)) || any(isinf(lo)) || any(isnan(hi)) || any(isinf(hi))
            || any(greaterThan(abs(lo),vec3(1000000))) || any(greaterThan(abs(hi),vec3(1000000))))return false;
    // Every uploaded local shape stays within [-1,2]. The one-cell guard includes overhangs.
    first=ivec3(floor(lo-1.0));last=ivec3(floor(hi+1.0));ivec3 size=last-first+1;
    return all(greaterThan(size,ivec3(0))) && all(lessThanEqual(size,ivec3(16))) && size.x*size.y*size.z<=2048;
}
bool worldCell(ivec3 block,vec3 lo,vec3 hi,inout ivec3 cached,inout int slot,out uvec4 cell,out uint base) {
    ivec3 section=(block>>4)+uWorldOriginSection;
    if(slot==-2 || any(notEqual(section,cached))){cached=section;slot=worldSlot(section);}
    if(slot<0)return false;
    uint index=uint((block.x&15)|((block.z&15)<<4)|((block.y&15)<<8));
    base=uint(slot)*uWorldSlotWords;
    uint p=base+index*4u;cell=uvec4(worldData[p],worldData[p+1u],worldData[p+2u],worldData[p+3u]);
    if(cell.y>uWorldShapeCapacity || cell.x>uWorldShapeCapacity-cell.y)return false;
    float friction=uintBitsToFloat(cell.z);
    if(isnan(friction) || isinf(friction) || friction<0.0 || (cell.w&~0xff3fu)!=0u)return false;
    // Unsupported contexts include overhanging/dynamic shapes. Unsupported cells pause only on actual overlap; fluid/fire are environment inputs.
    return (cell.w&8u)==0u || any(lessThanEqual(hi,vec3(block))) || any(greaterThanEqual(lo,vec3(block)+1.0));
}
void worldBox(uint base,uint shape,ivec3 block,out vec3 lo,out vec3 hi) {
    uint p=base+16384u+shape*8u;
    lo=vec3(block)+vec3(uintBitsToFloat(worldData[p]),uintBitsToFloat(worldData[p+1u]),uintBitsToFloat(worldData[p+2u]));
    hi=vec3(block)+vec3(uintBitsToFloat(worldData[p+4u]),uintBitsToFloat(worldData[p+5u]),uintBitsToFloat(worldData[p+6u]));
}
bool sweepWorld(Body b,vec3 p,vec3 motion,inout float closest,inout vec3 normal) {
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
            float time;vec3 hit;
            if(sweepBox(p,motion,boxLo-b.extentYaw.xyz,boxHi+b.extentYaw.xyz,time,hit) && time<=closest){closest=time;normal=hit;}
        }
    }
    return true;
}
// Jacobi can push beyond the predicted bounds. Validate the final contact correction before
// publishing it, including neighbour geometry; unknown sections must never become air.
bool coveredWorld(Body b) {
    vec3 lo=b.positionMass.xyz-b.extentYaw.xyz,hi=b.positionMass.xyz+b.extentYaw.xyz;
    ivec3 first,last,cached=ivec3(0);int slot=-2;
    if(!worldBounds(lo,hi,first,last))return false;
    if(clearWorld(first,last))return true;
    for(int z=first.z;z<=last.z;z++)for(int y=first.y;y<=last.y;y++)for(int x=first.x;x<=last.x;x++) {
        uvec4 cell;uint base;
        if(!worldCell(ivec3(x,y,z),lo,hi,cached,slot,cell,base))return false;
    }
    return true;
}
bool solveWorld(Body b,out vec3 correction,inout vec3 velocity,inout bool grounded,out float friction) {
    vec3 lo=b.positionMass.xyz-b.extentYaw.xyz,hi=b.positionMass.xyz+b.extentYaw.xyz;
    ivec3 first,last,cached=ivec3(0);int slot=-2;friction=-1.0;correction=vec3(0);
    vec3 positive=vec3(0),negative=vec3(0);
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
    if(any(bvec3(positive.x>0.0 && negative.x<0.0,positive.y>0.0 && negative.y<0.0,positive.z>0.0 && negative.z<0.0)))return false;
    correction=positive+negative;
    return true;
}
