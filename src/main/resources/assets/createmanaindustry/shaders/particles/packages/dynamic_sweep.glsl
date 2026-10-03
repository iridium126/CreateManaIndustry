layout(std430,binding=7) readonly buffer FastCells { uint fastCells[]; };
layout(std430,binding=6) readonly buffer StepVelocity { vec4 stepVelocity[]; };
layout(local_size_x=64) in;

void pauseCollision(Body b,uint i) {
    b.positionMass.xyz=b.previousSleep.xyz;
    b.velocityGround=stepVelocity[i];
    b.previousSleep.w=PACKAGE_COLLISION_FROZEN;
    dst[i]=b;
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
    if(!fast&&fastCells[hashCell(cellOf(b.positionMass.xyz))]==0u){dst[i]=b;return;}

    // Package dimensions are admitted at <= half a grid cell. Other package
    // displacement is bounded to 16 cells (32 blocks) per 20 Hz step; faster
    // bodies request a local pause to bound the GPU query. A long move already
    // clipped by the static-world sweep may still use its now-bounded path.
    float maximumMotion=uCellSize*PACKAGE_MAX_SWEEP_CELLS;
    bool longMove=any(greaterThan(abs(motion),vec3(maximumMotion)));
    float incomingSpeed=length(stepVelocity[i].xyz),currentSpeed=length(b.velocityGround.xyz);
    bool staticallyClipped=longMove && currentSpeed<incomingSpeed*.5
            && length(b.velocityGround.xyz-stepVelocity[i].xyz)>max(2.0,incomingSpeed*.5);
    if(longMove&&!staticallyClipped) { pauseCollision(b,i);return; }
    // The segment is already covered by min(start,end)..max(start,end). Only pad
    // for this body's extent and the largest admitted counterpart half-extent;
    // adding maximumMotion again made ordinary fast hits exceed the volume budget.
    vec3 padding=b.extentYaw.xyz+vec3(uCellSize*.5+1e-4);
    ivec3 lo=cellOf(min(start,b.positionMass.xyz)-padding);
    ivec3 hi=cellOf(max(start,b.positionMass.xyz)+padding);
    ivec3 size=hi-lo+1;
    if(any(lessThanEqual(size,ivec3(0))) || any(greaterThan(size,ivec3(32))) || size.x*size.y*size.z>PACKAGE_SWEEP_CELL_VOLUME) {
        pauseCollision(b,i);return;
    }

    uint visits=0u;
    float earliest=1.0;
    vec3 hitNormal=vec3(0);
    vec3 hitVelocity=vec3(0),hitMotion=vec3(0);
    float hitInverseMass=0.0;
    bool hit=false;
    for(int z=lo.z;z<=hi.z;z++)for(int y=lo.y;y<=hi.y;y++)for(int x=lo.x;x<=hi.x;x++) {
        ivec3 cell=ivec3(x,y,z);
        uint cursor,end;cellCursor(cell,cursor,end);
        while(cursor!=end) {
            uint j=cursorBody(cursor),next=cursorNext(cursor,j);
            if(++visits>uCandidateBudget) { pauseCollision(b,i);return; }
            if(j!=i && all(equal(cellOf(src[j].positionMass.xyz),cell))) {
                Body other=src[j];
                bool otherLive=other.previousSleep.w>=0.0 || other.previousSleep.w==PACKAGE_COLLISION_FROZEN;
                if(other.positionMass.w>0.0 && otherLive) {
                    vec3 otherMotion=other.positionMass.xyz-other.previousSleep.xyz;
                    if(all(lessThanEqual(abs(otherMotion),vec3(maximumMotion)))) {
                        bool otherFast=any(greaterThan(abs(otherMotion),max(other.extentYaw.xyz,vec3(1e-5))));
                        if(!fast&&!otherFast){cursor=next;continue;}
                        vec3 relativeStart=start-other.previousSleep.xyz;
                        vec3 relativeMotion=motion-otherMotion;
                        vec3 extent=b.extentYaw.xyz+other.extentYaw.xyz;
                        float time;vec3 normal;
                        if(sweepBox(relativeStart,relativeMotion,-extent,extent,time,normal) && time<earliest) {
                            earliest=time;hitNormal=normal;hitVelocity=other.velocityGround.xyz;hitMotion=otherMotion;
                            hitInverseMass=other.previousSleep.w==PACKAGE_COLLISION_FROZEN?0.0:other.positionMass.w;hit=true;
                        }
                    }
                }
            }
            cursor=next;
        }
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
