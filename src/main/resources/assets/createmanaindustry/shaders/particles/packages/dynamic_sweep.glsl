#pragma cmi_include packages/sweep_cells.glsl
layout(std430,binding=7) readonly buffer FastCells { SweepCell fastCells[]; };
layout(std430,binding=8) readonly buffer LongSweepBodies { uint longBodyCount; uint longBodies[]; };
layout(local_size_x=64) in;

void considerCandidate(Body b,uint i,bool fast,vec3 start,vec3 motion,uint j,
        inout float earliest,inout vec3 hitNormal,inout vec3 hitVelocity,inout vec3 hitMotion,
        inout float hitInverseMass,inout bool hit) {
    if(j==i)return;
    Body other=src[j];
    bool otherLive=other.previousSleep.w>=0.0 || other.previousSleep.w==PACKAGE_COLLISION_FROZEN;
    if(other.positionMass.w<=0.0 || !otherLive)return;
    vec3 otherMotion=other.positionMass.xyz-other.previousSleep.xyz;
    bool otherFast=any(greaterThan(abs(otherMotion),max(other.extentYaw.xyz,vec3(1e-5))));
    if(!fast&&!otherFast)return;
    // Cheap conservative cull avoids slab sweeps for distant packages in the
    // oversized-path fallback.
    vec3 lo=min(start,start+motion)-b.extentYaw.xyz,hi=max(start,start+motion)+b.extentYaw.xyz;
    vec3 otherLo=min(other.previousSleep.xyz,other.positionMass.xyz)-other.extentYaw.xyz;
    vec3 otherHi=max(other.previousSleep.xyz,other.positionMass.xyz)+other.extentYaw.xyz;
    if(any(greaterThan(lo,otherHi))||any(greaterThan(otherLo,hi)))return;
    vec3 relativeStart=start-other.previousSleep.xyz;
    vec3 relativeMotion=motion-otherMotion;
    vec3 extent=b.extentYaw.xyz+other.extentYaw.xyz;
    float time;vec3 normal;
    if(sweepBox(relativeStart,relativeMotion,-extent,extent,time,normal) && time<earliest) {
        earliest=time;hitNormal=normal;hitVelocity=other.velocityGround.xyz;hitMotion=otherMotion;
        hitInverseMass=other.previousSleep.w==PACKAGE_COLLISION_FROZEN?0.0:other.positionMass.w;hit=true;
    }
}

void main() {
    uint i=gl_GlobalInvocationID.x;
    if(i>=uCount)return;
    Body b=src[i];
    if(b.positionMass.w<=0.0 || b.previousSleep.w<0.0) { dst[i]=b;return; }

    vec3 start=b.previousSleep.xyz;
    vec3 motion=b.positionMass.xyz-start;
    // If each body's movement is below its own half-extent, even two
    // approaching bodies cannot cross the full pair extent in one step. Leave
    // those common slow contacts to the cheaper discrete Jacobi pass.
    bool fast=any(greaterThan(abs(motion),max(b.extentYaw.xyz,vec3(1e-5))));
    // Slow bodies must participate when the other body is fast. The prepass marks
    // conservative affected cells so stationary populations retain a cheap early out.
    if(!fast&&fastCells[hashCell(cellOf(b.positionMass.xyz))].positive.w==0u&&longBodyCount==0u){dst[i]=b;return;}

    // Package dimensions are admitted at <= half a grid cell. The ordinary
    // hashed query stays tightly bounded; unusually long or dense queries use
    // an exact sparse scan rather than leaving a body collision-frozen forever.
    float maximumMotion=uCellSize*PACKAGE_MAX_SWEEP_CELLS;
    bool longMove=any(greaterThan(abs(motion),vec3(maximumMotion)));
    // Slow counterparts can move by their own half extent. Fast counterparts
    // contribute their actual signed reach only in cells touched by this path.
    // This is conservative for both swept trajectories without padding every
    // body by the global maximum displacement.
    vec3 padding=b.extentYaw.xyz+vec3(uCellSize+1e-4);
    ivec3 lo=cellOf(min(start,b.positionMass.xyz)-padding);
    ivec3 hi=cellOf(max(start,b.positionMass.xyz)+padding);
    vec3 positive=vec3(0),negative=vec3(0);
    for(int z=lo.z;z<=hi.z;z++)for(int y=lo.y;y<=hi.y;y++)for(int x=lo.x;x<=hi.x;x++) {
        SweepCell reach=fastCells[hashCell(ivec3(x,y,z))];
        positive=max(positive,uintBitsToFloat(reach.positive.xyz));negative=max(negative,uintBitsToFloat(reach.negative.xyz));
    }
    // The aggregate fast-cell table may contain this body's own trajectory.
    // Subtracting its same-direction reach yields the relative endpoint extent:
    // slower co-moving bodies stay inside our sweep, while faster ones extend it
    // only by their excess displacement.
    positive=max(positive-max(motion,vec3(0)),vec3(0));
    negative=max(negative-max(-motion,vec3(0)),vec3(0));
    lo=cellOf(min(start,b.positionMass.xyz)-padding-negative);
    hi=cellOf(max(start,b.positionMass.xyz)+padding+positive);
    ivec3 size=hi-lo+1;
    bool fallback=longMove || any(lessThanEqual(size,ivec3(0)))
            || any(greaterThan(size,ivec3(32))) || size.x*size.y*size.z>PACKAGE_SWEEP_CELL_VOLUME;
    uint visits=0u;
    float earliest=1.0;
    vec3 hitNormal=vec3(0);
    vec3 hitVelocity=vec3(0),hitMotion=vec3(0);
    float hitInverseMass=0.0;
    bool hit=false;
    if(!fallback) {
        for(int z=lo.z;z<=hi.z&&!fallback;z++)for(int y=lo.y;y<=hi.y&&!fallback;y++)for(int x=lo.x;x<=hi.x&&!fallback;x++) {
            ivec3 cell=ivec3(x,y,z);
            uint cursor,end;cellCursor(cell,cursor,end);
            while(cursor!=end) {
                uint j=cursorBody(cursor),next=cursorNext(cursor,j);
                if(++visits>uCandidateBudget) { fallback=true;break; }
                if(j!=i && all(equal(cellOf(src[j].positionMass.xyz),cell)))
                    considerCandidate(b,i,fast,start,motion,j,earliest,hitNormal,hitVelocity,hitMotion,hitInverseMass,hit);
                cursor=next;
            }
        }
    }
    if(fallback) {
        // This path is reached only for rare long/high-volume sweeps or a highly
        // colliding bucket. Exact pair CCD over the compact body array is slower
        // than the grid, but bounded by the admitted package capacity and always
        // makes progress instead of freezing a legal high-speed package.
        earliest=1.0;hitNormal=vec3(0);hitVelocity=vec3(0);hitMotion=vec3(0);hitInverseMass=0.0;hit=false;
        for(uint j=0u;j<uCount;j++)considerCandidate(b,i,fast,start,motion,j,earliest,hitNormal,hitVelocity,hitMotion,hitInverseMass,hit);
    } else if(longBodyCount>0u) {
        for(uint k=0u;k<longBodyCount;k++)
            considerCandidate(b,i,fast,start,motion,longBodies[k],earliest,hitNormal,hitVelocity,hitMotion,hitInverseMass,hit);
    }
    if(!hit) { dst[i]=b;return; }

    vec3 hitPosition=start+motion*earliest;
    float inverseMass=max(b.positionMass.w,0.0);
    float totalInverseMass=inverseMass+hitInverseMass;
    float weight=totalInverseMass>0.0?inverseMass/totalInverseMass:0.0;
    vec3 relativeVelocity=b.velocityGround.xyz-hitVelocity;
    float normalVelocity=dot(relativeVelocity,hitNormal);
    if(normalVelocity<0.0 && totalInverseMass>0.0)
        b.velocityGround.xyz+=hitNormal*(-normalVelocity*weight);
    vec3 remainingRelative=(motion-hitMotion)*(1.0-earliest);
    float inward=dot(remainingRelative,hitNormal);
    b.positionMass.xyz=hitPosition+motion*(1.0-earliest);
    if(inward<0.0)b.positionMass.xyz+=hitNormal*(-inward*weight);
    if(hitNormal.y>.5)b.velocityGround.w=1.0;
    dst[i]=b;
}
