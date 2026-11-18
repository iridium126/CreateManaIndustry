vec3 center(StormTick tick) { return vec3(tick.centerX,tick.centerY,tick.centerZ); }
void home(StormTick tick,float seed,inout vec3 position,inout vec3 velocity) {
    cmiTyphoonHome(seed,tick.radius,tick.omega,tick.rotation,tick.conveyor,tick.conveyorRate,
        tick.speed,center(tick),position,velocity);
}
static const float dt=.05f;
void featherImpact(vec3 position,float incomingY){
    if(!featherEnabled)return;
    int x=int(floor(position.x*.5f)),y=int(floor(position.y*.5f)),z=int(floor(position.z*.5f));
    uint hash=uint(x)*0x8da6b343u^uint(y)*0xd8163841u^uint(z)*0xcb1ab31fu;
    hash^=hash>>16u;
    float strength=clamp(.5f-incomingY*.25f,0.f,3.5f);
    for(uint probe=0;probe<8;probe++){
        uint index=(hash+probe)&(featherImpactLimit-1);
        int state;
        InterlockedCompareExchange(featherCells[index].state,0,1,state);
        if(state==1)return;
        if(state==2){
            if(featherCells[index].x!=x||featherCells[index].y!=y||featherCells[index].z!=z)continue;
            int oldState;
            InterlockedCompareExchange(featherCells[index].state,2,1,oldState);
            if(oldState!=2)return;
        }
        if(state==0||strength>featherCells[index].impact.w){
            featherCells[index].x=x;featherCells[index].y=y;featherCells[index].z=z;featherCells[index].impact=float4(position,strength);
        }
        DeviceMemoryBarrier(); InterlockedExchange(featherCells[index].state,2,state);
        return;
    }
}
uint liveSweep(inout StormCollision collision,inout vec3 position,inout vec3 velocity){
    float incomingY=velocity.y;
    uint flags=collision.sweep(position,velocity,dt);
    if(collision.hitDown)featherImpact(collision.impactPoint,incomingY);
    return flags;
}
vec3 repelPlayers(vec3 position,uint count){
    static const float radius=3.f,weight=14.f;
    vec3 result=0;
    for(uint i=0;i<count;i++){
        vec3 delta=position-vec3(players[i]);
        float distanceSquared=dot(delta,delta);
        if(distanceSquared>1e-6f&&distanceSquared<radius*radius){
            float distance=sqrt(distanceSquared);
            result=result+delta*(weight*(radius-distance)/(radius*distance));
        }
    }
    return result;
}
bool nearPlayer(vec3 position,uint count,bool feet){
    float radiusSquared=feet?36.f:16.f;
    for(uint i=0;i<count;i++){
        float4 player=players[i];
        vec3 delta=position-vec3(player.x,feet?player.w:player.y,player.z);
        if(dot(delta,delta)<=radiusSquared)return true;
    }
    return false;
}
static const uint outerArrived=1u, outerPublished=2u;
void publishOuterArrival(inout StormStepMember member,uint index){
    uint row;
    batches.InterlockedAdd(CMI_RESOURCE_STORM_OUTER_BATCH_OFFSET,1u,row);
    if(row>=CMI_RESOURCE_STORM_OUTER_BATCH_LIMIT)return;
    batches.Store(CMI_RESOURCE_STORM_OUTER_BATCH_OFFSET+16u+row*8u,index);
    batches.Store(CMI_RESOURCE_STORM_OUTER_BATCH_OFFSET+16u+row*8u+4u,member.outerCycle);
    member.outerState|=outerPublished;
}
vec3 correctedVelocity(inout StormStepMember member,StormTick tick,vec3 position,vec3 velocity,float cap,bool column) {
    float4 correction=member.correctionVelocityStrength;
    float age=columnLocalClock(tick.seconds,member.correctionPositionTime.w);
    bool hit=correction.w>2.5f;
    float window=hit?.25f:1.5f;
    if(correction.w<=.5f||age<0.f||age>window||(column&&!hit))return velocity;
    vec3 reference=correction.xyz;
    vec3 desired=vec3(member.correctionPositionTime)+reference*age;
    float gain=hit?24.f:8.f*(.25f+.75f*clamp(age/window,0.f,1.f));
    velocity=velocity+(desired-position)*min(gain*dt,.5f);
    velocity=velocity+(reference-velocity)*min(2.5f*dt,.35f);
    float speed=length(velocity);
    return speed>cap?velocity*(cap/speed):velocity;
}

bool stepColumn(StormTick tick,inout StormStepMember member,
        inout StormDomeMember assignment,inout StormRoute route,inout StormCollision terrain,vec3 target,vec3 homeVelocity) {
    StormColumn column=columns[assignment.slot];
    if(column.identity!=tick.identity||column.id!=assignment.id||!column.state
        ||assignment.phase==DomeReleasePending)return false;
    uint rank=assignment.rank;
    uint row=assignment.slot*columnMembers+rank;
    StormColumnMember state=columnStates[row];
    
    float now=domeElapsed(tick,column.start);
    vec3 position=vec3(member.position),velocity=vec3(member.velocity);
    if(column.restoreToken&&(state.identity!=tick.identity||state.id!=column.id||state.restoreToken!=column.restoreToken)){
        StormColumnRestore snapshot=restores[row];
        if(snapshot.identity!=tick.identity||snapshot.id!=column.id||snapshot.token!=column.restoreToken
            ||snapshot.parts!=(1u<<CMI_RESOURCE_STORM_COLUMN_RESTORE_PARTS)-1u){
            member.previous=member.position;
            member.velocity=float4(0,0,0,0);
            member.terrainState=StormDiscontinuity;
            return true;
        }
        restoreColumn(state,column,rank,snapshot,position,velocity);
    }
    if(state.identity==tick.identity&&state.id==column.id&&state.replaying){
        for(int i=0;i<4&&state.replayCursor<now-.0001f;i++){
            float step=min(dt,now-state.replayCursor);
            ColumnMotion motion=advanceColumn(state,column,rank,terrain,position,velocity,target,homeVelocity,state.replayCursor+step,step);
            velocity=motion.engaged?motion.velocity:
                velocity+((target-position)*24.f+(homeVelocity-velocity)*6.f)*step;
            float speed=length(velocity);
            if(speed>motion.speedCap)velocity=velocity*(motion.speedCap/speed);
            terrain.sweep(position,velocity,step);
            state.replayCursor+=step;
        }
        state.replaying=state.replayCursor<now-.0001f;
        columnStates[row]=state;
        if(!state.replaying)batches.InterlockedOr(columnBatchOffset(assignment.slot)+16u+rank/32*4u,1u<<(rank&31));
        member.previous=member.position=float4(position,0.f);
        member.velocity=float4(velocity,0.f);
        member.terrainState=StormDiscontinuity;
        return true;
    }
    ColumnMotion move=advanceColumn(state,column,rank,terrain,position,velocity,target,homeVelocity,now,dt);
    columnStates[row]=state;
    if(!move.engaged)return false;
    velocity=move.velocity;
    float speed=length(velocity);
    if(speed>move.speedCap)velocity=velocity*(move.speedCap/speed);
    velocity=correctedVelocity(member,tick,position,velocity,move.speedCap,true);
    vec3 before=position;
    float impactY=move.bloomStarted?min(member.velocity.y,velocity.y):velocity.y;
    uint flags=terrain.sweep(position,velocity,dt);
    if(move.bloomStarted)featherImpact(move.bloomPoint,impactY);
    else if(terrain.hitDown)featherImpact(terrain.impactPoint,impactY);
    if((flags&StormCoverageUnknown)!=0u&&state.phase==columnBloom){
        state.phase=columnExit;
        state.release=now;
        move.marker=5.f;
    }
    if(move.exited&&(flags&StormCoverageUnknown)==0u)velocity=move.exitVelocity;
    columnStates[row]=state;
    columnContact(assignment.slot,column,state,tick,before,position,now,dt,flags);
    if((move.bloomStarted||state.phase==columnBloom)&&asuint(tick.targetAfter.w)!=0u
        &&length(position-vec3(tick.targetAfter))<32.f){
        uint old;
        InterlockedCompareExchange(columns[assignment.slot].soundPlayed,0u,1u,old);
        if(old==0u)batches.Store4(columnBatchOffset(assignment.slot)+64u,asuint(float4(position,1.f)));
    }
    if(move.exited||now>=column.end){
        batches.InterlockedOr(columnBatchOffset(assignment.slot)+8u+rank/32*4u,1u<<(rank&31));
        assignment.phase=DomeReturn;assignment.progressSeconds=0.f;
        route=(StormRoute)0;
    }
    member.previous=float4(before,0.f);member.position=float4(position,0.f);member.velocity=float4(velocity,move.marker);
    member.terrainState=flags;
    return true;
}
