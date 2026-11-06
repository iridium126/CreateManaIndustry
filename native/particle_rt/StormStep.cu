#include <cuda_runtime.h>
#include "StormStep.h"
#include "Spatial.h"

namespace {
using uint=unsigned int;
struct vec3 {
    float x, y, z;
    __device__ explicit vec3(float v) : x(v), y(v), z(v) {}
    __device__ vec3(float x, float y, float z) : x(x), y(y), z(z) {}
    __device__ explicit vec3(float4 v) : x(v.x), y(v.y), z(v.z) {}
    __device__ vec3 operator+(vec3 b) const { return vec3(x+b.x,y+b.y,z+b.z); }
    __device__ vec3 operator-(vec3 b) const { return vec3(x-b.x,y-b.y,z-b.z); }
    __device__ vec3 operator*(float a) const { return vec3(x*a,y*a,z*a); }
    __device__ float4 row(float w) const { return make_float4(x,y,z,w); }
};
__device__ float length(vec3 v) { return sqrtf(v.x*v.x+v.y*v.y+v.z*v.z); }
__device__ float fract(float v) { return v-floorf(v); }
__device__ float sign(float v) { return float(v>0.f)-float(v<0.f); }
__device__ float clamp(float v,float a,float b) { return fminf(fmaxf(v,a),b); }
__device__ float max(float a,float b) { return fmaxf(a,b); }
#include "StormCollision.cuh"

// Explicit float overloads keep both implementations in FP32. The build
// also disables FMA contraction to retain the member-seed hash sequence.
#define floor floorf
#define cos cosf
#define sin sinf
#define sqrt sqrtf
#define log logf
#define CMI_STORM_FN __device__
#define CMI_STORM_OUT(T) T&
#define precise
#include "storm_home.glsl"
#undef precise
#undef CMI_STORM_FN
#undef CMI_STORM_OUT
#undef log
#undef sqrt
#undef sin
#undef cos
#undef floor
#include "StormNavigation.cuh"
#include "StormDome.cuh"
#include "StormHelix.cuh"
#include "StormContact.cuh"
#include "StormColumn.cuh"

__device__ vec3 center(StormTick tick) { return vec3(tick.centerX,tick.centerY,tick.centerZ); }
__device__ void home(StormTick tick,float seed,vec3& position,vec3& velocity) {
    cmiTyphoonHome(seed,tick.radius,tick.omega,tick.rotation,tick.conveyor,tick.conveyorRate,
        tick.speed,center(tick),position,velocity);
}
__device__ constexpr float dt=.05f;
__device__ void featherImpact(StormFeatherCell* cells,vec3 point,float incomingY){
    if(!cells)return;
    int x=__float2int_rd(point.x*.5f),y=__float2int_rd(point.y*.5f),z=__float2int_rd(point.z*.5f);
    unsigned hash=unsigned(x)*0x8da6b343u^unsigned(y)*0xd8163841u^unsigned(z)*0xcb1ab31fu;
    hash^=hash>>16u;
    float strength=clamp(.5f-incomingY*.25f,0.f,3.5f);
    for(unsigned probe=0;probe<8;probe++){
        auto& cell=cells[(hash+probe)&(featherImpactLimit-1)];
        int state=atomicCAS(&cell.state,0,1);
        if(state==1)return;
        if(state==2){
            if(cell.x!=x||cell.y!=y||cell.z!=z)continue;
            if(atomicCAS(&cell.state,2,1)!=2)return;
        }
        if(state==0||strength>cell.impact.w){
            cell.x=x;cell.y=y;cell.z=z;cell.impact=point.row(strength);
        }
        __threadfence();atomicExch(&cell.state,2);
        return;
    }
}
__device__ unsigned liveSweep(StormCollision& collision,StormFeatherCell* cells,vec3& position,vec3& velocity){
    float incomingY=velocity.y;
    unsigned flags=collision.sweep(position,velocity,dt);
    if(collision.hitDown)featherImpact(cells,collision.impactPoint,incomingY);
    return flags;
}
__device__ vec3 repelPlayers(vec3 position,const float4* players,unsigned count){
    constexpr float radius=3.f,weight=14.f;
    vec3 result(0.f);
    for(unsigned i=0;i<count;i++){
        auto delta=position-vec3(players[i]);
        float distanceSquared=dot(delta,delta);
        if(distanceSquared>1e-6f&&distanceSquared<radius*radius){
            float distance=sqrtf(distanceSquared);
            result=result+delta*(weight*(radius-distance)/(radius*distance));
        }
    }
    return result;
}
__device__ bool nearPlayer(vec3 position,const float4* players,unsigned count,bool feet){
    float radiusSquared=feet?36.f:16.f;
    for(unsigned i=0;i<count;i++){
        float4 player=players[i];
        vec3 delta=position-vec3(player.x,feet?player.w:player.y,player.z);
        if(dot(delta,delta)<=radiusSquared)return true;
    }
    return false;
}
constexpr unsigned outerArrived=1u, outerPublished=2u;
__device__ void publishOuterArrival(StormOuterBatch& batch,StormStepMember& member,unsigned index){
    unsigned row=atomicAdd(&batch.count,1u);
    if(row>=CMI_RESOURCE_STORM_OUTER_BATCH_LIMIT)return;
    batch.rows[row]={index,member.outerCycle};
    member.outerState|=outerPublished;
}
__device__ vec3 correctedVelocity(StormStepMember& member,StormTick tick,vec3 position,vec3 velocity,float cap,bool column) {
    auto correction=member.correctionVelocityStrength;
    float age=columnLocalClock(tick.seconds,member.correctionPositionTime.w);
    bool hit=correction.w>2.5f;
    float window=hit?.25f:1.5f;
    if(correction.w<=.5f||age<0.f||age>window||(column&&!hit))return velocity;
    vec3 reference(correction);
    vec3 desired=vec3(member.correctionPositionTime)+reference*age;
    float gain=hit?24.f:8.f*(.25f+.75f*clamp(age/window,0.f,1.f));
    velocity=velocity+(desired-position)*fminf(gain*dt,.5f);
    velocity=velocity+(reference-velocity)*fminf(2.5f*dt,.35f);
    float speed=length(velocity);
    return speed>cap?velocity*(cap/speed):velocity;
}

__device__ bool stepColumn(StormDomeView view,StormTick tick,StormStepMember& member,
        StormDomeMember& assignment,StormRoute& route,StormCollision& terrain,vec3 target,vec3 homeVelocity) {
    auto& column=view.columns[assignment.slot];
    if(column.identity!=tick.identity||column.id!=assignment.id||!column.state
        ||assignment.phase==DomeReleasePending)return false;
    unsigned rank=assignment.rank;
    unsigned row=assignment.slot*columnMembers+rank;
    auto& state=view.columnStates[row];
    auto& batch=view.batch->columns[assignment.slot];
    float now=domeElapsed(tick,column.start);
    auto position=vec3(member.position),velocity=vec3(member.velocity);
    if(column.restoreToken&&(state.identity!=tick.identity||state.id!=column.id||state.restoreToken!=column.restoreToken)){
        const auto& snapshot=view.columnRestores[row];
        if(snapshot.identity!=tick.identity||snapshot.id!=column.id||snapshot.token!=column.restoreToken
            ||snapshot.parts!=(1u<<CMI_RESOURCE_STORM_COLUMN_RESTORE_PARTS)-1u){
            member.previous=member.position;
            member.velocity=vec3(0.f).row(0.f);
            member.terrainState=StormDiscontinuity;
            return true;
        }
        restoreColumn(state,column,rank,snapshot,position,velocity);
    }
    if(state.identity==tick.identity&&state.id==column.id&&state.replaying){
        for(int i=0;i<4&&state.replayCursor<now-.0001f;i++){
            float step=fminf(dt,now-state.replayCursor);
            auto motion=advanceColumn(state,column,rank,terrain,position,velocity,target,homeVelocity,state.replayCursor+step,step);
            velocity=motion.engaged?motion.velocity:
                velocity+((target-position)*24.f+(homeVelocity-velocity)*6.f)*step;
            float speed=length(velocity);
            if(speed>motion.speedCap)velocity=velocity*(motion.speedCap/speed);
            terrain.sweep(position,velocity,step);
            state.replayCursor+=step;
        }
        state.replaying=state.replayCursor<now-.0001f;
        if(!state.replaying)atomicOr(&batch.installed[rank/32],1u<<(rank&31));
        member.previous=member.position=position.row(0.f);
        member.velocity=velocity.row(0.f);
        member.terrainState=StormDiscontinuity;
        return true;
    }
    auto move=advanceColumn(state,column,rank,terrain,position,velocity,target,homeVelocity,now,dt);
    if(!move.engaged)return false;
    velocity=move.velocity;
    float speed=length(velocity);
    if(speed>move.speedCap)velocity=velocity*(move.speedCap/speed);
    velocity=correctedVelocity(member,tick,position,velocity,move.speedCap,true);
    auto before=position;
    float impactY=move.bloomStarted?fminf(member.velocity.y,velocity.y):velocity.y;
    unsigned flags=terrain.sweep(position,velocity,dt);
    if(move.bloomStarted)featherImpact(view.featherCells,move.bloomPoint,impactY);
    else if(terrain.hitDown)featherImpact(view.featherCells,terrain.impactPoint,impactY);
    if((flags&StormCoverageUnknown)!=0u&&state.phase==columnBloom){
        state.phase=columnExit;
        state.release=now;
        move.marker=5.f;
    }
    if(move.exited&&(flags&StormCoverageUnknown)==0u)velocity=move.exitVelocity;
    columnContact(batch,column,state,tick,before,position,now,dt,flags);
    if((move.bloomStarted||state.phase==columnBloom)&&__float_as_uint(tick.targetAfter.w)!=0u
        &&length(position-vec3(tick.targetAfter))<32.f&&atomicCAS(&column.soundPlayed,0u,1u)==0u)
        batch.sound=position.row(1.f);
    if(move.exited||now>=column.end){
        atomicOr(&batch.completed[rank/32],1u<<(rank&31));
        assignment.phase=DomeReturn;assignment.progressSeconds=0.f;
        route={};
    }
    member.previous=before.row(0.f);member.position=position.row(0.f);member.velocity=velocity.row(move.marker);
    member.terrainState=flags;
    return true;
}
}

extern "C" __global__ void compactStormFeatherImpacts(const StormFeatherCell* cells,StormFeatherReceipt* receipt) {
    unsigned index=blockIdx.x*blockDim.x+threadIdx.x;
    if(index<featherImpactLimit&&cells[index].state==2)receipt->rows[atomicAdd(&receipt->count,1u)]=cells[index].impact;
}

__device__ void applyStormControlRow(StormStepMember* members,StormTick tick,StormControl command,StormDomeView dome,unsigned rank) {
    unsigned index=command.member+rank;
    unsigned id=unsigned(command.scope),slot=unsigned(command.scope>>32);
    if(command.op==CMI_RESOURCE_STORM_CONTROL_COLUMN_STATE){
        auto& column=dome.columns[slot];
        bool same=column.identity==tick.identity&&column.id==id;
        unsigned state=command.flags&3u;
        if(!state){if(same)column.state=0;return;}
        if(!same){
            if((command.flags&CMI_RESOURCE_STORM_DOME_CONTROL_CREATE)==0u)return;
            column={};column.identity=tick.identity;column.id=id;
        }else if(!column.state)return;
        column.state=state;
        column.seed=command.member;
        column.landingTop=command.a;
        column.start=command.b.x;column.assemble=command.b.y;column.touch=command.b.z;column.end=command.b.w;
        return;
    }
    if(command.op==CMI_RESOURCE_STORM_CONTROL_COLUMN_RESTORE||command.op==CMI_RESOURCE_STORM_CONTROL_COLUMN_RESTORE_ROW){
        auto& column=dome.columns[slot];
        if(column.identity!=tick.identity||column.id!=id)return;
        if(command.op==CMI_RESOURCE_STORM_CONTROL_COLUMN_RESTORE){
            if(rank==0u&&column.state!=0u){
                column.restoreToken=command.member;
                column.restoreElapsed=command.a.x;
                column.state=1u;
            }
            auto& restore=dome.columnRestores[slot*columnMembers+rank];
            restore.identity=tick.identity;restore.id=id;restore.token=command.member;restore.parts=0u;
        }else if(column.state!=0u){
            unsigned record=command.member/CMI_RESOURCE_STORM_COLUMN_RESTORE_PARTS;
            unsigned part=command.member%CMI_RESOURCE_STORM_COLUMN_RESTORE_PARTS;
            auto& restore=dome.columnRestores[slot*columnMembers+record];
            if(restore.identity!=tick.identity||restore.id!=id||restore.token!=column.restoreToken)return;
            restore.rows[part*2u]=command.a;restore.rows[part*2u+1u]=command.b;
            atomicOr(&restore.parts,1u<<part);
        }
        return;
    }
    if(command.op==CMI_RESOURCE_STORM_CONTROL_DOME_STATE||command.op==CMI_RESOURCE_STORM_CONTROL_HELIX_STATE){
        auto& attack=dome.attacks[slot];
        bool helix=command.op==CMI_RESOURCE_STORM_CONTROL_HELIX_STATE;
        unsigned kind=helix?RegionalHelix:RegionalDome;
        unsigned phase=command.flags&3u;
        bool same=attack.identity==tick.identity&&attack.id==id&&attack.kind==kind;
        if(phase==0){
            if(same)attack.state=0;
            return;
        }
        if(!same){
            if((command.flags&CMI_RESOURCE_STORM_DOME_CONTROL_CREATE)==0)return;
            attack={};attack.identity=tick.identity;attack.id=id;attack.kind=kind;
            if(helix)dome.helixes[slot]={};
            for(auto& pause:attack.paused)pause=__uint_as_float(0x7f800000u);
        }else if(attack.state==0||phase<attack.state)return;
        unsigned previousState=attack.state;
        attack.state=phase;
        if(helix){
            auto& data=dome.helixes[slot];
            attack.start=command.a.x;
            attack.lockedAt=command.a.y;
            attack.end=command.a.z;
            data.freeze=command.a.w;
            data.speed=command.b.x;
            data.cap=command.b.y;
            data.targetArc=command.b.z;
            data.terrainReady=command.b.w>.5f;
            data.count=command.member;
            if(phase==2u)data.recovery[0]={};
            return;
        }
        attack.centerYaw=command.a;
        attack.start=command.b.x;
        if(previousState!=2u||attack.state!=2u)
            attack.lockedAt=previousState==1u&&attack.state==2u?domeElapsed(tick,attack.start):command.b.y;
        attack.end=command.b.z;
        attack.radius=command.b.w;
        return;
    }
    if(command.op==CMI_RESOURCE_STORM_CONTROL_HELIX_FRAME||command.op==CMI_RESOURCE_STORM_CONTROL_HELIX_RECOVERY){
        const auto& attack=dome.attacks[slot];
        if(attack.identity!=tick.identity||attack.id!=id||attack.kind!=RegionalHelix||!attack.state)return;
        auto& data=dome.helixes[slot];
        if(command.op==CMI_RESOURCE_STORM_CONTROL_HELIX_FRAME){
            unsigned frame=command.member/4u,row=(command.member%4u)*2u;
            if(frame>=data.count)return;
            data.frames[frame][row]=command.a;
            data.frames[frame][row+1u]=command.b;
        }else if(attack.state!=2u){
            data.recovery[command.member]=command.a;
            if(command.member+1<CMI_RESOURCE_STORM_HELIX_RECOVERY_ROWS)data.recovery[command.member+1]=command.b;
        }
        return;
    }
    if(command.op==CMI_RESOURCE_STORM_CONTROL_DOME_PAUSE){
        auto& attack=dome.attacks[slot];
        if(attack.identity==tick.identity&&attack.id==id&&attack.kind==RegionalDome)
            attack.paused[command.flags]=fminf(attack.paused[command.flags],command.a.x);
        return;
    }
    if(command.op==CMI_RESOURCE_STORM_CONTROL_DOME_ASSIGN||command.op==CMI_RESOURCE_STORM_CONTROL_HELIX_ASSIGN){
        const auto& attack=dome.attacks[slot];
        bool helix=command.op==CMI_RESOURCE_STORM_CONTROL_HELIX_ASSIGN;
        if(attack.identity!=tick.identity||attack.id!=id||!attack.state
            ||attack.kind!=(helix?RegionalHelix:RegionalDome))return;
        auto& assignment=dome.assignments[index];
        if(assignment.identity==tick.identity&&assignment.id==id&&assignment.slot==slot&&assignment.rank==command.flags)return;
        assignment={};
        assignment.identity=tick.identity;assignment.id=id;assignment.slot=slot;assignment.rank=command.flags;
        assignment.kind=attack.kind;
        assignment.phase=DomeGather;
        if(helix)assignment.helixLocal=command.a;
        assignment.pauseSeen=__uint_as_float(0x7f800000u);
        dome.routes[index]={};
        return;
    }
    if(command.op==CMI_RESOURCE_STORM_CONTROL_COLUMN_ASSIGN){
        auto& column=dome.columns[slot];
        if(column.identity!=tick.identity||column.id!=id||!column.state)return;
        column.roster[command.flags]=index+1u;
        auto& assignment=dome.assignments[index];
        if(assignment.identity==tick.identity&&assignment.kind==RegionalColumn&&assignment.id==id
            &&assignment.slot==slot&&assignment.rank==command.flags)return;
        assignment={};assignment.identity=tick.identity;assignment.kind=RegionalColumn;
        assignment.id=id;assignment.slot=slot;assignment.rank=command.flags;assignment.phase=DomeGather;
        dome.routes[index]={};
        return;
    }
    if(command.op==CMI_RESOURCE_STORM_CONTROL_DOME_RELEASE||command.op==CMI_RESOURCE_STORM_CONTROL_COLUMN_RELEASE){
        auto& assignment=dome.assignments[index];
        bool column=command.op==CMI_RESOURCE_STORM_CONTROL_COLUMN_RELEASE;
        if(assignment.identity==tick.identity&&assignment.id==id&&assignment.slot==slot
            &&column==(assignment.kind==RegionalColumn)
            &&assignment.phase!=DomeReturn&&assignment.phase!=DomeHome){
            assignment.phase=DomeReleasePending;
        }
        return;
    }
    StormStepMember& member=members[index];
    if(command.op==CMI_RESOURCE_STORM_CONTROL_SPAWN) {
        // A previously confirmed death can precede the local spawn budget.
        if(member.identity==tick.identity&&member.state!=StormAbsent)return;
        StormStepMember born{};
        born.identity=tick.identity;born.birthSequence=tick.sequence;born.state=StormAlive;
        born.seed=cmiStormMemberSeed(float(tick.seed),index);born.hp=20.f;
        if(member.identity==tick.identity){
            born.correctionPositionTime=member.correctionPositionTime;
            born.correctionVelocityStrength=member.correctionVelocityStrength;
            born.outerTarget=member.outerTarget;
            born.outerCycle=member.outerCycle;
            born.outerState=member.outerState;
        }
        vec3 point(0.f),motion(0.f),position(0.f),velocity(0.f);
        home(tick,born.seed,point,motion);
        cmiStormBirth(index,(command.flags&1u)!=0,tick.radius,tick.speed,center(tick),
            point,motion,position,velocity);
        born.previous=born.position=position.row(0.f);born.velocity=velocity.row(0.f);
        born.spatialSteering=velocity.row(tick.speed);
        member=born;
    } else if(command.op==CMI_RESOURCE_STORM_CONTROL_DAMAGE) {
        bool died=(command.flags&1u)!=0;
        if(member.identity!=tick.identity||member.state==StormAbsent) {
            if(died){StormStepMember dead{};dead.identity=tick.identity;dead.state=StormDead;member=dead;}
            return;
        }
        if(member.state!=StormAlive)return;
        // The server's death flag controls lifecycle, including when its
        // damage history differs from the local HP history.
        member.hp=died?0.f:fmaxf(.0001f,member.hp-command.a.x);
        member.hurt=1.f;member.damageLight=command.a.w;
        member.velocity.x=member.velocity.x*.5f-command.a.y;
        member.velocity.z=member.velocity.z*.5f-command.a.z;
        if(died)member.state=StormCorpse;
    } else if(command.op==CMI_RESOURCE_STORM_CONTROL_CORRECTION) {
        if(member.identity!=tick.identity){
            member={};
            member.identity=tick.identity;
        }
        if(member.state==StormDead)return;
        member.correctionPositionTime=command.a;member.correctionVelocityStrength=command.b;
    } else if(command.op==CMI_RESOURCE_STORM_CONTROL_OUTER_START){
        if(member.identity!=tick.identity){member={};member.identity=tick.identity;}
        if(member.state==StormCorpse||member.state==StormDead||command.flags<=member.outerCycle)return;
        member.outerTarget=command.a;member.outerCycle=command.flags;member.outerState=0;
        if(dome.assignments){dome.assignments[index]={};dome.routes[index]={};}
    } else if(command.op==CMI_RESOURCE_STORM_CONTROL_OUTER_RELEASE&&member.identity==tick.identity
        &&member.outerCycle==command.flags){
        member.outerState=outerArrived|outerPublished;
    }
}

extern "C" __global__ void applyStormControl(StormStepMember* members,StormTick tick,StormControl command,StormDomeView dome) {
    unsigned rank=blockIdx.x*blockDim.x+threadIdx.x;
    if(rank<command.span)applyStormControlRow(members,tick,command,dome,rank);
}

extern "C" __global__ void applyStormControlBatch(StormStepMember* members,StormTick tick,const StormControl* controls,
        unsigned first,unsigned count,StormDomeView dome) {
    unsigned rank=blockIdx.x*blockDim.x+threadIdx.x;
    if(rank<count)applyStormControlRow(members,tick,controls[first+rank],dome,0);
}

extern "C" __global__ void prepareStormDome(StormDomeView view,StormTick tick) {
    unsigned slot=blockIdx.x,lane=threadIdx.x;
    if(slot==0&&lane==0){
        view.batch->outer.count=0;
        if(tick.positionSnapshot)view.batch->positions.count=0;
    }
    if(!view.attacks)return;
    if(slot==0&&lane<columnSlots){
        auto& batch=view.batch->columns[lane];
        batch={};
        if(view.columns){
            const auto& column=view.columns[lane];
            if(column.identity==tick.identity&&column.state!=0u){
                batch.id=column.id;batch.restoreToken=column.restoreToken;
            }
        }
    }
    auto& attack=view.attacks[slot];
    if(lane==0){
        view.batch->attackIds[slot]=attack.identity==tick.identity&&attack.state!=0?attack.id:0;
        view.batch->blocked[slot]=view.batch->unknown[slot]=0;
        view.batch->contacts[slot]={};
        view.batch->helixReferences[slot]={};
        if(view.batch->attackIds[slot]!=0u&&attack.kind==RegionalHelix){
            auto& data=view.helixes[slot];
            float elapsed=domeElapsed(tick,attack.start);
            auto current=helixReference(attack,data,elapsed);
            data.current=current.pack();
            data.previous=helixReference(attack,data,elapsed-dt).pack();
            view.batch->helixReferences[slot]={current.version,current.valid?1u:0u,current.seconds,0u};
        }
    }
    for(unsigned i=lane;i<domeBitmapWords;i+=blockDim.x){
        view.batch->ready[slot][i]=view.batch->completed[slot][i]=0;
    }
    if(lane<domeFlows)view.batch->paused[slot][lane]=attack.paused[lane];
}

extern "C" __global__ void finishStormDome(StormDomeView view) {
    unsigned slot=blockIdx.x,lane=threadIdx.x;
    if(view.attacks[slot].kind!=RegionalDome)return;
    __shared__ unsigned flowChanged[domeFlows];
    if(lane<domeFlows){
        auto& pause=view.attacks[slot].paused[lane];
        float observed=view.batch->paused[slot][lane];
        flowChanged[lane]=observed<pause;
        pause=observed;
    }
    __syncthreads();
    for(unsigned word=lane;word<domeBitmapWords;word+=blockDim.x){
        auto bits=view.batch->ready[slot][word];
        for(unsigned seen=bits;seen;seen&=seen-1){
            unsigned bit=unsigned(__ffs(seen)-1);
            if(flowChanged[view.flows[word*32+bit]])bits&=~(1u<<bit);
        }
        view.batch->ready[slot][word]=bits;
    }
}

extern "C" __global__ void prepareStormSpatial(SpatialParams params){
    unsigned index=blockIdx.x*blockDim.x+threadIdx.x;
    if(index>=params.members)return;
    const auto& member=params.stormMembers[index];
    bool alive=member.identity==params.epoch&&member.state==StormAlive;
    auto position=member.position;
    position.w=.34f;
    params.instances[index]=movingInstance(position,member.velocity,index,alive,params.unit);
}

extern "C" __global__ void advanceStormVortex(StormStepMember* members,StormTick tick,StormTerrain terrain,
        StormDomeView view,const float4* players,const float4* hints) {
    unsigned index=blockIdx.x*blockDim.x+threadIdx.x;
    if(index>=tick.population)return;
    StormStepMember& member=members[index];
    if(member.identity!=tick.identity||member.state==StormAbsent||member.state==StormDead
        ||member.birthSequence==tick.sequence)return;
    vec3 position(member.position),velocity(member.velocity);
    StormCollision collision{terrain};
    vec3 separation=vec3(hints[index])*(fminf(6.f,length(velocity))*6.f);
    if(member.state==StormCorpse){
        if(member.hp<=-1.f){
            member.state=StormDead;
            member.poofSequence=tick.sequence;
            return;
        }
        member.previous=member.position;
        velocity=velocity*(1.f-1.88621f*dt);
        velocity=correctedVelocity(member,tick,position,velocity,tick.speed,false);
        member.terrainState=liveSweep(collision,view.featherCells,position,velocity);
        member.position=position.row(0.f);member.velocity=velocity.row(member.damageLight);
        member.age+=dt;member.hurt=fmaxf(0.f,member.hurt-2.f*dt);
        member.hp=fmaxf(-1.f,member.hp-dt);
        return;
    }
    member.age+=dt;member.hurt=fmaxf(0.f,member.hurt-2.f*dt);
    member.hp=fminf(20.f,member.hp+2.f*dt);
    if(member.outerCycle&&(member.outerState&outerPublished)==0u){
        if((member.outerState&outerArrived)!=0u){
            publishOuterArrival(view.batch->outer,member,index);
        }else{
            auto destination=vec3(member.outerTarget);
            velocity=velocity+((destination-position)*24.f-velocity*6.f+repelPlayers(position,players,tick.playerCount))*dt;
            float speed=length(velocity);
            if(speed>tick.speed)velocity=velocity*(tick.speed/speed);
            velocity=correctedVelocity(member,tick,position,velocity,tick.speed,false);
            member.previous=member.position;
            auto end=position+velocity*dt;
            if(collision.body(position).complete&&collision.body(end).complete){
                member.terrainState=liveSweep(collision,view.featherCells,position,velocity);
            }else{
                position=end;
                member.terrainState=StormCoverageUnknown;
            }
            member.position=position.row(0.f);member.velocity=velocity.row(0.f);
            member.spatialSteering=make_float4(0.f,0.f,0.f,0.f);
            if(length(position-destination)<=2.f){
                member.outerState=outerArrived;
                publishOuterArrival(view.batch->outer,member,index);
            }
            return;
        }
    }
    vec3 target(0.f),reference(0.f);
    home(tick,member.seed,target,reference);
    auto proposed=velocity+((target-position)*24.f+(reference-velocity)*6.f)*dt;
    StormDomeMember* assignment=view.assignments?view.assignments+index:nullptr;
    if(assignment&&assignment->identity==tick.identity&&assignment->kind==RegionalColumn
        &&assignment->phase==DomeGather){
        const auto& column=view.columns[assignment->slot];
        if(column.identity==tick.identity&&column.id==assignment->id&&column.state&&domeElapsed(tick,column.start)<0.f)
            assignment=nullptr;
    }
    StormRoute* route=nullptr;
    const StormDome* attack=nullptr;
    const StormHelix* helix=nullptr;
    HelixMemberSample helixPoint;
    DomeSample sample;
    NavigationResult navigation{vec3(0.f),false,false,false};
    auto queryDirection=reference;
    bool queryMotion=true;
    float elapsed=0.f,cap=tick.speed;
    bool ordinary=true;
    if(assignment&&assignment->identity==tick.identity&&assignment->phase!=DomeHome){
        route=view.routes+index;
        bool column=assignment->kind==RegionalColumn;
        if(column&&assignment->phase!=DomeReturn){
            if(stepColumn(view,tick,member,*assignment,*route,collision,target,reference)){
                member.spatialSteering=make_float4(0.f,0.f,0.f,0.f);
                return;
            }
            if(assignment->phase!=DomeReturn){
                assignment->phase=DomeReturn;assignment->progressSeconds=0.f;*route={};
            }
        }
        if(!column)attack=view.attacks+assignment->slot;
        if(assignment->kind==RegionalHelix)helix=view.helixes+assignment->slot;
        if(attack)elapsed=domeElapsed(tick,attack->start);
        if(attack&&(assignment->phase==DomeReleasePending||attack->id!=assignment->id||attack->identity!=tick.identity
            ||attack->kind!=assignment->kind||!attack->state||elapsed-dt>=attack->end)){
            if(assignment->phase!=DomeReturn){assignment->phase=DomeReturn;assignment->progressSeconds=0.f;*route={};}
        }
        if(assignment->phase==DomeReturn){
            float age=assignment->progressSeconds;
            if(age>=.5f&&length(position-target)<2.f){
                assignment->phase=DomeHome;
                *route={};
            }else{
                proposed=proposed+(separation+repelPlayers(position,players,tick.playerCount))*dt;
                ordinary=false;
                if(helix)cap=fmaxf(cap,length(velocity)-24.f*dt);
                if(age<.5f&&!helix&&!column){
                    float current=quintic(clamp(age/.5f,0.f,1.f)),next=quintic(clamp((age+dt)/.5f,0.f,1.f));
                    float blend=clamp((next-current)/fmaxf(1.f-current,.000001f),0.f,1.f);
                    proposed=velocity*(1.f-blend)+proposed*blend;
                }
                navigation=navigateStorm(collision,*route,view.pathSearches,tick.tick,index,position,target,proposed,cap);
                proposed=navigation.altered?gatherVelocity(velocity,navigation.desired,cap,dt):navigation.desired;
                assignment->progressSeconds=age+dt;
            }
        }else if(elapsed>=0.f&&helix&&helix->count){
            ordinary=false;
            auto local=vec3(assignment->helixLocal);
            helixPoint=helixSample(*attack,*helix,HelixReference(helix->current),local,elapsed);
            auto previous=helixSample(*attack,*helix,HelixReference(helix->previous),local,elapsed-dt);
            queryDirection=helixPoint.velocity;
            if(attack->state==2u&&elapsed>=attack->lockedAt){
                proposed=helixAttackVelocity(*assignment,elapsed,dt,position,velocity,helixPoint,previous)+separation*dt;
                cap=helix->cap;
            }else{
                cap=12.f;
                proposed=gatherVelocity(velocity,helixGatherDesired(position,velocity,helixPoint,previous)+separation*(1.f/6.f),cap,dt);
            }
        }else if(elapsed>=0.f&&!helix&&view.records){
            ordinary=false;
            queryMotion=false;
            sample=domeWorldSample(view,*attack,assignment->rank,elapsed);
            if(attack->state==2u&&elapsed>=attack->lockedAt){
                proposed=domeAttackVelocity(view,*attack,*assignment,elapsed,dt,position,velocity,sample);
                float age=clamp((elapsed-assignment->joinTime)/.5f,0.f,1.f);
                cap=fmaxf(6.f,assignment->joinOffset.w+(6.f-assignment->joinOffset.w)*quintic(age));
            }else{
                cap=8.f;
                auto desired=domeGatherDesired(view,*attack,assignment->rank,elapsed,dt,position,velocity,sample);
                navigation=navigateStorm(collision,*route,view.pathSearches,tick.tick,index,position,sample.point,desired,cap);
                proposed=gatherVelocity(velocity,navigation.desired,cap,dt);
            }
        }else if(elapsed>=0.f)atomicAdd(&view.batch->unknown[assignment->slot],1u);
    }else assignment=nullptr;
    if(ordinary)proposed=proposed+(separation+repelPlayers(position,players,tick.playerCount))*dt;
    velocity=proposed;
    float speed=length(velocity);
    if(speed>cap)velocity=velocity*(cap/speed);
    velocity=correctedVelocity(member,tick,position,velocity,cap,false);
    member.previous=member.position;
    auto motionBeforeSweep=velocity;
    member.terrainState=liveSweep(collision,view.featherCells,position,velocity);
    constexpr unsigned fullyKnownEmbedding=StormCoverageUnknown|StormEmbedded;
    if((member.terrainState&fullyKnownEmbedding)==StormEmbedded&&tick.tick>=member.recoveryTick
        &&atomicAdd(view.pathSearches+1,1u)<16u){
        member.recoveryTick=tick.tick+2u;
        if(escapeStormBody(collision,position,velocity)){
            member.previous=position.row(0.f);
            member.terrainState|=StormDiscontinuity;
            if(route)*route={};
        }
    }
    if(assignment&&helix&&helix->count&&elapsed>=0.f&&assignment->phase==DomeGather){
        auto feet=StormCollision::block(helixPoint.point+vec3(.0001f));
        auto head=StormCollision::block(helixPoint.point+vec3(.0001f,.6001f,.0001f));
        int feetPage=collision.lookup(StormCollision::section(feet));
        int headPage=collision.lookup(StormCollision::section(head));
        bool occupied=collision.solid(feet,feetPage)||collision.solid(head,headPage);
        bool unknown=!helix->current.valid||!helix->terrainReady||feetPage==-1||headPage==-1
            ||(member.terrainState&StormCoverageUnknown)!=0u;
        auto delta=helixPoint.point-vec3(member.previous);
        auto forward=unit(delta,vec3(0.f));
        bool clipped=(member.terrainState&StormTerrainClipped)!=0u;
        bool stalled=clipped&&length(delta)>.4f&&dot(position-vec3(member.previous),forward)
            <fmaxf(0.f,dot(motionBeforeSweep*dt,forward))*.1f;
        assignment->blockedSeconds=stalled?assignment->blockedSeconds+dt:0.f;
        bool blocked=occupied||assignment->blockedSeconds>=.5f;
        bool ready=!unknown&&!blocked&&(member.terrainState&(StormDiscontinuity|StormEmbedded))==0u
            &&length(position-helixPoint.point)<=.4f&&length(velocity-helixPoint.velocity)<=1.f;
        assignment->streak=ready?min(3u,assignment->streak+1u):0u;
        unsigned slot=assignment->slot,rank=assignment->rank;
        if(assignment->streak==3u)atomicOr(&view.batch->ready[slot][rank/32],1u<<(rank&31));
        if(unknown)atomicAdd(&view.batch->unknown[slot],1u);
        else if(blocked)atomicAdd(&view.batch->blocked[slot],1u);
    }
    if(assignment&&attack&&!helix&&view.records&&elapsed>=0.f&&assignment->phase==DomeGather){
        unsigned slot=assignment->slot,rank=assignment->rank,flow=view.flows[rank];
        float pause=attack->paused[flow];
        auto status=bodyTerrain(collision,sample.point);
        bool unknown=status==CorridorUnknown||navigation.unknown||(member.terrainState&StormCoverageUnknown)!=0u;
        bool clipped=(member.terrainState&StormTerrainClipped)!=0u;
        float error=length(position-sample.point);
        if(!unknown&&status==CorridorBlocked&&isinf(pause))
            atomicMin(reinterpret_cast<unsigned*>(&view.batch->paused[slot][flow]),__float_as_uint(fmaxf(0.f,elapsed-dt)));
        if(pause!=assignment->pauseSeen){
            assignment->pauseSeen=pause;assignment->streak=0;assignment->tracked=false;
            assignment->blockedSeconds=assignment->progressSeconds=0.f;
        }
        bool occupied=!unknown&&status==CorridorBlocked;
        assignment->blockedSeconds=occupied&&!isinf(pause)?assignment->blockedSeconds+dt:0.f;
        bool following=assignment->tracked||error<.04f;
        bool ready=following&&!unknown&&!occupied&&(member.terrainState&StormDiscontinuity)==0u
            &&error<=.4f&&length(velocity-sample.velocity)<=1.f;
        assignment->tracked=ready&&error<.04f;
        assignment->streak=ready?min(3u,assignment->streak+1u):0u;
        bool noProgress=!unknown&&error>.4f&&(clipped||navigation.blocked);
        if(noProgress){
            if(assignment->progressSeconds==0.f)assignment->initialError=error;
            assignment->progressSeconds+=dt;
        }else assignment->progressSeconds=0.f;
        bool stuck=assignment->progressSeconds>=.5f&&assignment->initialError-error<.05f&&route->searched&&route->noRoute;
        if(assignment->blockedSeconds>=.5f||stuck)domeComplete(view,*assignment,*route);
        else if(assignment->streak==3u)atomicOr(&view.batch->ready[slot][rank/32],1u<<(rank&31));
        if(unknown)atomicAdd(&view.batch->unknown[slot],1u);
        else if(occupied)atomicAdd(&view.batch->blocked[slot],1u);
    }
    bool canContact=__float_as_uint(tick.targetAfter.w)!=0u
        &&(member.terrainState&(StormCoverageUnknown|StormDiscontinuity|StormEmbedded))==0u;
    if(assignment&&helix&&assignment->phase==DomeAttack&&(member.terrainState&StormDiscontinuity)==0u){
        if(canContact){
            HelixContactReference path{*attack,*helix,HelixReference(helix->current),vec3(assignment->helixLocal)};
            stormContact(view.batch->contacts[assignment->slot],tick.targetBefore,tick.targetAfter,
                tick.seconds,attack->lockedAt,attack->end,elapsed,dt,vec3(member.previous),position,member.terrainState,cap,path);
        }
        auto displacement=position-vec3(member.previous);
        bool clipped=(member.terrainState&StormTerrainClipped)!=0u;
        bool blocked=(member.terrainState&StormCoverageUnknown)==0u&&helixBlocked(*assignment,clipped,dt,
            dot(motionBeforeSweep*dt,helixPoint.axis),dot(displacement,helixPoint.axis));
        if(elapsed>=attack->end||blocked||dot(position-helixPoint.center,helixPoint.axis)>=helix->targetArc)
            domeComplete(view,*assignment,*route);
    }
    if(assignment&&attack&&!helix&&view.records&&assignment->phase==DomeAttack&&(member.terrainState&StormDiscontinuity)==0u){
        if(canContact){
            DomeContactReference path{view,*attack,assignment->rank};
            stormContact(view.batch->contacts[assignment->slot],tick.targetBefore,tick.targetAfter,
                tick.seconds,attack->lockedAt,attack->end,elapsed,dt,vec3(member.previous),position,member.terrainState,cap,path);
        }
        bool slow=length(position-vec3(member.previous))<.2f*dt;
        bool blocked=(member.terrainState&StormTerrainClipped)!=0u||slow;
        assignment->blockedSeconds=blocked?assignment->blockedSeconds+dt:0.f;
        if(elapsed>=attack->end||assignment->blockedSeconds>=.5f||domePastExit(view,*attack,*assignment,sample,position))
            domeComplete(view,*assignment,*route);
    }
    member.position=position.row(0.f);
    bool carry=assignment&&attack&&assignment->phase==DomeAttack&&nearPlayer(position,players,tick.playerCount,true);
    member.velocity=velocity.row(carry?-4.f:0.f);
    member.spatialSteering=queryDirection.row(queryMotion?cap:0.f);
}

extern "C" __global__ void publishStormPositions(const StormStepMember* members,StormTick tick,
        const float4* players,StormDomeBatch* batches){
    unsigned index=blockIdx.x*blockDim.x+threadIdx.x;
    if(index>=tick.population)return;
    const auto& member=members[index];
    if(member.identity!=tick.identity||member.state!=StormAlive)return;
    vec3 position(member.position);
    if(!nearPlayer(position,players,tick.playerCount,false))return;
    auto& output=batches->positions;
    unsigned row=atomicAdd(&output.count,1u);
    if(row>=CMI_RESOURCE_STORM_POSITION_BATCH_LIMIT)return;
    vec3 relative=position-center(tick),velocity(member.velocity);
    output.rows[row]={float(index),0.f,relative.x,relative.y,relative.z,velocity.x,velocity.y,velocity.z};
}

__device__ float absoluteColumnTime(float start,float elapsed){
    float result=start+elapsed;
    if(result<0.f)return result+104857.6f;
    return result>=104857.6f?result-104857.6f:result;
}
__device__ void writeColumnVector(float* row,vec3 value){
    row[0]=value.x;row[1]=value.y;row[2]=value.z;
}
extern "C" __global__ void captureStormColumns(const StormStepMember* members,StormTick tick,StormDomeView view){
    unsigned capture=blockIdx.x,rank=threadIdx.x;
    auto request=tick.columnCaptures[capture];
    float* row=view.batch->columnCaptures[capture][rank];
    row[0]=255.f;
    if(!view.columns)return;
    const auto& column=view.columns[request.x];
    if(column.identity!=tick.identity||column.id!=request.y||column.state!=1u||!column.roster[rank])return;
    unsigned index=column.roster[rank]-1u;
    const auto& member=members[index];
    const auto& state=view.columnStates[request.x*columnMembers+rank];
    const auto& assignment=view.assignments[index];
    if(member.identity!=tick.identity||member.state!=StormAlive||state.identity!=tick.identity
        ||state.id!=column.id||state.replaying||assignment.id!=column.id||assignment.kind!=RegionalColumn)return;
    auto origin=vec3(column.landingTop);
    row[0]=float(state.phase);
    writeColumnVector(row+1,vec3(member.position)-origin);
    writeColumnVector(row+4,vec3(member.velocity));
    row[7]=state.phase==0u?0.f:absoluteColumnTime(column.start,state.descentStart);
    row[8]=state.touchdown<0.f?-1.f:absoluteColumnTime(column.start,state.touchdown);
    row[9]=state.release<0.f?-1.f:absoluteColumnTime(column.start,state.release);
    row[10]=state.distance;
    writeColumnVector(row+11,vec3(state.origin)-origin);
    writeColumnVector(row+14,vec3(state.initialVelocity));
    writeColumnVector(row+17,state.phase==0u?vec3(0.f):vec3(state.descentOrigin)-origin);
    writeColumnVector(row+20,vec3(state.descentDirection));
    writeColumnVector(row+23,vec3(state.tangent));
    row[26]=state.bloomRadius;row[27]=state.bloomAngle;row[28]=row[29]=0.f;
}

extern "C" __global__ void sealStormTick(StormStepReceipt* result,StormTick tick,const StormDisplayHeader* display) {
    *result={tick.sequence,tick.tick,tick.identity,display?display->count:0u};
}

extern "C" __global__ void prepareStormDisplay(StormDisplayHeader* header,StormTick tick,unsigned capacity) {
    *header={CMI_RESOURCE_STORM_DISPLAY_MAGIC,CMI_RESOURCE_ABI,0u,capacity,tick.sequence,tick.tick,tick.identity,0u,{}};
}
extern "C" __global__ void publishStormDisplay(StormDisplayHeader* header,const StormStepMember* members,
        StormTick tick,StormDomeView view) {
    unsigned index=blockIdx.x*blockDim.x+threadIdx.x;
    bool active=false,poof=false;
    if(index<tick.population&&members[index].identity==tick.identity){
        const auto& member=members[index];
        uint64_t shown=tick.projectedIdentity==tick.identity?tick.projectedSequence:0;
        poof=member.poofSequence>shown;
        active=member.state==StormAlive||member.state==StormCorpse||poof;
    }
    unsigned mask=__ballot_sync(0xffffffffu,active);
    if(!active)return;
    unsigned leader=unsigned(__ffs(mask)-1),lane=threadIdx.x&31u;
    unsigned base=0;
    if(lane==leader)base=atomicAdd(&header->count,unsigned(__popc(mask)));
    base=__shfl_sync(mask,base,leader);
    unsigned rank=base+__popc(mask&((1u<<lane)-1u));
    auto& out=reinterpret_cast<StormDisplayRecord*>(header+1)[rank];
    const auto& member=members[index];
    uint32_t attack=0,attackRank=0,flags=member.terrainState&15u;
    if(member.birthSequence==tick.sequence)flags|=CMI_RESOURCE_STORM_DISPLAY_BIRTH;
    if(member.state==StormAlive&&view.assignments){
        const auto& assignment=view.assignments[index];
        if(assignment.identity==tick.identity&&assignment.phase!=DomeHome){
            attack=assignment.id;attackRank=assignment.rank;
            flags|=(assignment.phase<<CMI_RESOURCE_STORM_DISPLAY_PHASE_SHIFT)
                |(assignment.kind<<CMI_RESOURCE_STORM_DISPLAY_KIND_SHIFT)
                |(assignment.slot<<CMI_RESOURCE_STORM_DISPLAY_SLOT_SHIFT);
            if(assignment.kind==RegionalColumn){
                unsigned columnRank=assignment.slot*columnMembers+assignment.rank;
                auto& state=view.columnStates[columnRank];
                auto* columns=reinterpret_cast<StormColumnDisplay*>(reinterpret_cast<StormDisplayRecord*>(header+1)+header->capacity);
                auto& display=columns[columnRank];
                bool curve=state.identity==tick.identity&&state.id==assignment.id&&!state.replaying
                    &&(member.terrainState&(StormTerrainClipped|StormDiscontinuity|StormEmbedded))==0u
                    &&(state.phase==columnDescent||state.phase==columnBloom);
                display.key=make_uint4(curve?state.id:0u,state.phase,__float_as_uint(state.bloomAngle),0u);
                if(curve){
                    display.originDistance=make_float4(state.descentOrigin.x,state.descentOrigin.y,state.descentOrigin.z,state.distance);
                    display.directionRadius=make_float4(state.descentDirection.x,state.descentDirection.y,state.descentDirection.z,state.bloomRadius);
                    if(state.phase==columnDescent)
                        for(unsigned knot=0;knot<CMI_RESOURCE_STORM_DISPLAY_COLUMN_ARC_NODES;knot++)display.arc[knot]=state.arc[knot];
                }
            }
        }
    }
    out.position=member.position;out.previous=member.previous;out.velocity=member.velocity;
    if(poof){
        flags|=CMI_RESOURCE_STORM_DISPLAY_POOF;
        out.position.w=__uint_as_float(unsigned(member.poofSequence));
        out.previous.w=__uint_as_float(unsigned(member.poofSequence>>32));
        out.velocity.w=member.damageLight;
    }
    out.appearance=make_float4(member.hp,member.age,member.hurt,member.seed);
    out.key=make_uint4(index,attack,attackRank,flags);
}
extern "C" __global__ void sealStormDisplay(StormDisplayHeader* header) { header->ready=1u; }
