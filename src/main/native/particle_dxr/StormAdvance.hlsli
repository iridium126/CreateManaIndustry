void stepMember(uint index, StormTick tick, inout StormStepMember member,
        inout StormDomeMember assignment, inout StormRoute route) {
    vec3 position=member.position.xyz,velocity=member.velocity.xyz;
    StormCollision collision=(StormCollision)0;
    vec3 separation=hints[index].xyz*(min(6.f,length(velocity))*6.f);
    if(member.state==StormCorpse){
        if(member.hp<=-1.f){
            member.state=StormDead;
            member.poofSequence=tick.sequence;
            return;
        }
        member.previous=member.position;
        velocity=velocity*(1.f-1.88621f*dt);
        velocity=correctedVelocity(member,tick,position,velocity,tick.speed,false);
        member.terrainState=liveSweep(collision,position,velocity);
        member.position=float4(position,0.f);member.velocity=float4(velocity,member.damageLight);
        member.age+=dt;member.hurt=max(0.f,member.hurt-2.f*dt);
        member.hp=max(-1.f,member.hp-dt);
        return;
    }
    member.age+=dt;member.hurt=max(0.f,member.hurt-2.f*dt);
    member.hp=min(20.f,member.hp+2.f*dt);
    if(member.outerCycle&&(member.outerState&outerPublished)==0u){
        if((member.outerState&outerArrived)!=0u){
            publishOuterArrival(member,index);
        }else{
            vec3 destination=vec3(member.outerTarget);
            velocity=velocity+((destination-position)*24.f-velocity*6.f+repelPlayers(position,tick.playerCount))*dt;
            float speed=length(velocity);
            if(speed>tick.speed)velocity=velocity*(tick.speed/speed);
            velocity=correctedVelocity(member,tick,position,velocity,tick.speed,false);
            member.previous=member.position;
            vec3 end=position+velocity*dt;
            if(collision.body(position).complete&&collision.body(end).complete){
                member.terrainState=liveSweep(collision,position,velocity);
            }else{
                position=end;
                member.terrainState=StormCoverageUnknown;
            }
            member.position=float4(position,0.f);member.velocity=float4(velocity,0.f);
            member.spatialSteering=float4(0.f,0.f,0.f,0.f);
            if(length(position-destination)<=2.f){
                member.outerState=outerArrived;
                publishOuterArrival(member,index);
            }
            return;
        }
    }
    vec3 target=0,reference=0;
    home(tick,member.seed,target,reference);
    vec3 proposed=velocity+((target-position)*24.f+(reference-velocity)*6.f)*dt;
    bool assigned=assignment.identity==tick.identity;
    if(assigned&&assignment.kind==RegionalRing&&assignment.phase!=DomeHome){
        StormColumn ring=columns[columnSlots+assignment.slot];
        if(ring.identity!=tick.identity||ring.id!=assignment.id||!ring.state)assignment.phase=DomeHome;
        else{
            float age=domeElapsed(tick,ring.ringAxisStart.w);
            if(age>=0.f){
                float angle=float(assignment.rank)*.3926990817f-age*.5235987756f;
                vec3 axis=ring.ringAxisStart.xyz,right=ring.ringRight.xyz,travel=ring.ringVelocity.xyz;
                vec3 radial=1.75f*(cos(angle)*right+sin(angle)*cross(axis,right));
                vec3 slot=ring.landingTop.xyz+clamp(domeElapsed(tick,ring.landingTop.w),0.f,.1f)*travel+radial;
                vec3 tangent=travel-.5235987756f*cross(axis,radial);
                vec3 desired=tangent+(slot-position)*4.f;
                velocity=lerp(velocity,desired*(8.f/max(8.f,length(desired))),min(1.f,12.f*dt));
                velocity=velocity*(8.f/max(8.f,length(velocity)));
                member.previous=member.position;
                member.terrainState=liveSweep(collision,position,velocity);
                member.position=float4(position,0.f);member.velocity=float4(velocity,0.f);
                member.spatialSteering=0;
                return;
            }
        }
        assigned=false;
    }
    if(assigned&&assignment.identity==tick.identity&&assignment.kind==RegionalColumn
        &&assignment.phase==DomeGather){
        StormColumn column=columns[assignment.slot];
        if(column.identity==tick.identity&&column.id==assignment.id&&column.state&&domeElapsed(tick,column.start)<0.f)
            assigned=false;
    }
    bool hasRoute=false;
    StormDome attackData=(StormDome)0;
    bool hasAttack=false;
    StormHelix helixData=(StormHelix)0;
    bool hasHelix=false;
    HelixMemberSample helixPoint=(HelixMemberSample)0;
    DomeSample value=(DomeSample)0;
    NavigationResult navigation=(NavigationResult)0;
    vec3 queryDirection=reference;
    bool queryMotion=true;
    float elapsed=0.f,cap=tick.speed;
    bool ordinary=true;
    if(assigned&&assignment.identity==tick.identity&&assignment.phase!=DomeHome){
        hasRoute=true;
        bool column=assignment.kind==RegionalColumn;
        if(column&&assignment.phase!=DomeReturn){
            if(stepColumn(tick,member,assignment,route,collision,target,reference)){
                member.spatialSteering=float4(0.f,0.f,0.f,0.f);
                return;
            }
            if(assignment.phase!=DomeReturn){
                assignment.phase=DomeReturn;assignment.progressSeconds=0.f;route=(StormRoute)0;
            }
        }
        hasAttack=!column;
        if(hasAttack)attackData=attacks[assignment.slot];
        hasHelix=assignment.kind==RegionalHelix;
        if(hasHelix)helixData=helixes[assignment.slot];
        if(hasAttack)elapsed=domeElapsed(tick,attackData.start);
        if(hasAttack&&(assignment.phase==DomeReleasePending||attackData.id!=assignment.id||attackData.identity!=tick.identity
            ||attackData.kind!=assignment.kind||!attackData.state||elapsed-dt>=attackData.end)){
            if(assignment.phase!=DomeReturn){assignment.phase=DomeReturn;assignment.progressSeconds=0.f;route=(StormRoute)0;}
        }
        if(assignment.phase==DomeReturn){
            float age=assignment.progressSeconds;
            if(age>=.5f&&length(position-target)<2.f){
                assignment.phase=DomeHome;
                route=(StormRoute)0;
            }else{
                proposed=proposed+(separation+repelPlayers(position,tick.playerCount))*dt;
                ordinary=false;
                if(hasHelix)cap=max(cap,length(velocity)-24.f*dt);
                if(age<.5f&&!hasHelix&&!column){
                    float current=quintic(clamp(age/.5f,0.f,1.f)),next=quintic(clamp((age+dt)/.5f,0.f,1.f));
                    float blend=clamp((next-current)/max(1.f-current,.000001f),0.f,1.f);
                    proposed=velocity*(1.f-blend)+proposed*blend;
                }
                navigation=navigateStorm(collision,route,tick.tick,index,position,target,proposed,cap);
                proposed=navigation.altered?gatherVelocity(velocity,navigation.desired,cap,dt):navigation.desired;
                assignment.progressSeconds=age+dt;
            }
        }else if(elapsed>=0.f&&hasHelix&&helixData.count){
            ordinary=false;
            vec3 local=vec3(assignment.helixLocal);
            helixPoint=helixSample(attackData,helixData,unpackHelix(helixData.current),local,elapsed);
            HelixMemberSample previous=helixSample(attackData,helixData,unpackHelix(helixData.previous),local,elapsed-dt);
            queryDirection=helixPoint.velocity;
            if(attackData.state==2u&&elapsed>=attackData.lockedAt){
                proposed=helixAttackVelocity(assignment,elapsed,dt,position,velocity,helixPoint,previous)+separation*dt;
                cap=helixData.cap;
            }else{
                cap=12.f;
                proposed=gatherVelocity(velocity,helixGatherDesired(position,velocity,helixPoint,previous)+separation*(1.f/6.f),cap,dt);
            }
        }else if(elapsed>=0.f&&!hasHelix&&(tick.activity&2u)){
            ordinary=false;
            queryMotion=false;
            value=domeWorldSample(attackData,assignment.rank,elapsed);
            if(attackData.state==2u&&elapsed>=attackData.lockedAt){
                proposed=domeAttackVelocity(attackData,assignment,elapsed,dt,position,velocity,value);
                float age=clamp((elapsed-assignment.joinTime)/.5f,0.f,1.f);
                cap=max(6.f,assignment.joinOffset.w+(6.f-assignment.joinOffset.w)*quintic(age));
            }else{
                cap=8.f;
                vec3 desired=domeGatherDesired(attackData,assignment.rank,elapsed,dt,position,velocity,value);
                navigation=navigateStorm(collision,route,tick.tick,index,position,value.position,desired,cap);
                proposed=gatherVelocity(velocity,navigation.desired,cap,dt);
            }
        }else if(elapsed>=0.f)batches.InterlockedAdd(CMI_RESOURCE_STORM_DOME_UNKNOWN_OFFSET+assignment.slot*4u,1u);
    }else assigned=false;
    if(ordinary)proposed=proposed+(separation+repelPlayers(position,tick.playerCount))*dt;
    velocity=proposed;
    float speed=length(velocity);
    if(speed>cap)velocity=velocity*(cap/speed);
    velocity=correctedVelocity(member,tick,position,velocity,cap,false);
    member.previous=member.position;
    vec3 motionBeforeSweep=velocity;
    member.terrainState=liveSweep(collision,position,velocity);
    static const uint fullyKnownEmbedding=StormCoverageUnknown|StormEmbedded;
    if((member.terrainState&fullyKnownEmbedding)==StormEmbedded&&tick.tick>=member.recoveryTick
        &&claimSearch(1,16)){
        member.recoveryTick=tick.tick+2u;
        if(escapeStormBody(collision,position,velocity)){
            member.previous=float4(position,0.f);
            member.terrainState|=StormDiscontinuity;
            if(hasRoute)route=(StormRoute)0;
        }
    }
    if(assigned&&hasHelix&&helixData.count&&elapsed>=0.f&&assignment.phase==DomeGather){
        int3 feet=voxelBlock(helixPoint.position+vec3(.0001f));
        int3 head=voxelBlock(helixPoint.position+vec3(.0001f,.6001f,.0001f));
        int feetPage=collision.lookup(voxelSection(feet));
        int headPage=collision.lookup(voxelSection(head));
        bool occupied=collision.solid(feet,feetPage)||collision.solid(head,headPage);
        bool unknown=!helixData.current.valid||!helixData.terrainReady||feetPage==-1||headPage==-1
            ||(member.terrainState&StormCoverageUnknown)!=0u;
        vec3 delta=helixPoint.position-vec3(member.previous);
        vec3 forward=unit(delta,vec3(0.f));
        bool clipped=(member.terrainState&StormTerrainClipped)!=0u;
        bool stalled=clipped&&length(delta)>.4f&&dot(position-vec3(member.previous),forward)
            <max(0.f,dot(motionBeforeSweep*dt,forward))*.1f;
        assignment.blockedSeconds=stalled?assignment.blockedSeconds+dt:0.f;
        bool blocked=occupied||assignment.blockedSeconds>=.5f;
        bool ready=!unknown&&!blocked&&(member.terrainState&(StormDiscontinuity|StormEmbedded))==0u
            &&length(position-helixPoint.position)<=.4f&&length(velocity-helixPoint.velocity)<=1.f;
        assignment.streak=ready?min(3u,assignment.streak+1u):0u;
        uint slot=assignment.slot,rank=assignment.rank;
        if(assignment.streak==3u)batches.InterlockedOr(domeBitmapOffset(CMI_RESOURCE_STORM_DOME_READY_OFFSET,slot,rank/32),1u<<(rank&31));
        if(unknown)batches.InterlockedAdd(CMI_RESOURCE_STORM_DOME_UNKNOWN_OFFSET+slot*4u,1u);
        else if(blocked)batches.InterlockedAdd(CMI_RESOURCE_STORM_DOME_BLOCKED_OFFSET+slot*4u,1u);
    }
    if(assigned&&hasAttack&&!hasHelix&&(tick.activity&2u)&&elapsed>=0.f&&assignment.phase==DomeGather){
        uint slot=assignment.slot,rank=assignment.rank,flow=domeFlowsDevice[rank];
        float pause=attackData.paused[flow];
        int status=bodyTerrain(collision,value.position);
        bool unknown=status==CorridorUnknown||(member.terrainState&StormCoverageUnknown)!=0u;
        bool clipped=(member.terrainState&StormTerrainClipped)!=0u;
        float error=length(position-value.position);
        if(!unknown&&status==CorridorBlocked&&isinf(pause))
            InterlockedMin(domePauseBits[slot*domeFlows+flow],asuint(max(0.f,elapsed-dt)));
        if(pause!=assignment.pauseSeen){
            assignment.pauseSeen=pause;assignment.streak=0;assignment.tracked=false;
            assignment.blockedSeconds=assignment.progressSeconds=0.f;
        }
        bool occupied=!unknown&&status==CorridorBlocked;
        assignment.blockedSeconds=occupied&&!isinf(pause)?assignment.blockedSeconds+dt:0.f;
        bool following=assignment.tracked||error<.04f;
        bool ready=following&&!unknown&&!occupied&&(member.terrainState&StormDiscontinuity)==0u
            &&error<=.4f&&length(velocity-value.velocity)<=1.f;
        assignment.tracked=ready&&error<.04f;
        assignment.streak=ready?min(3u,assignment.streak+1u):0u;
        bool noProgress=!unknown&&!navigation.unknown&&error>.4f&&(clipped||navigation.blocked);
        if(noProgress){
            if(assignment.progressSeconds==0.f)assignment.initialError=error;
            assignment.progressSeconds+=dt;
        }else assignment.progressSeconds=0.f;
        bool stuck=assignment.progressSeconds>=.5f&&assignment.initialError-error<.05f&&route.searched&&route.noRoute;
        if(assignment.blockedSeconds>=.5f||stuck)domeComplete(assignment,route);
        else if(assignment.streak==3u)batches.InterlockedOr(domeBitmapOffset(CMI_RESOURCE_STORM_DOME_READY_OFFSET,slot,rank/32),1u<<(rank&31));
        if(unknown)batches.InterlockedAdd(CMI_RESOURCE_STORM_DOME_UNKNOWN_OFFSET+slot*4u,1u);
        else if(occupied)batches.InterlockedAdd(CMI_RESOURCE_STORM_DOME_BLOCKED_OFFSET+slot*4u,1u);
    }
    bool canContact=asuint(tick.targetAfter.w)!=0u
        &&(member.terrainState&(StormCoverageUnknown|StormDiscontinuity|StormEmbedded))==0u;
    if(assigned&&hasHelix&&assignment.phase==DomeAttack&&(member.terrainState&StormDiscontinuity)==0u){
        if(canContact){
            stormContact(assignment.slot,tick.targetBefore,tick.targetAfter,
                tick.seconds,attackData.lockedAt,attackData.end,elapsed,dt,vec3(member.previous),position,member.terrainState,cap,attackData,helixData,assignment.rank,assignment.helixLocal.xyz,hasHelix);
        }
        vec3 displacement=position-vec3(member.previous);
        bool clipped=(member.terrainState&StormTerrainClipped)!=0u;
        bool blocked=(member.terrainState&StormCoverageUnknown)==0u&&helixBlocked(assignment,clipped,dt,
            dot(motionBeforeSweep*dt,helixPoint.axis),dot(displacement,helixPoint.axis));
        if(elapsed>=attackData.end||blocked||dot(position-helixPoint.center,helixPoint.axis)>=helixData.targetArc)
            domeComplete(assignment,route);
    }
    if(assigned&&hasAttack&&!hasHelix&&(tick.activity&2u)&&assignment.phase==DomeAttack&&(member.terrainState&StormDiscontinuity)==0u){
        if(canContact){
            stormContact(assignment.slot,tick.targetBefore,tick.targetAfter,
                tick.seconds,attackData.lockedAt,attackData.end,elapsed,dt,vec3(member.previous),position,member.terrainState,cap,attackData,helixData,assignment.rank,assignment.helixLocal.xyz,hasHelix);
        }
        bool slow=length(position-vec3(member.previous))<.2f*dt;
        bool blocked=(member.terrainState&StormTerrainClipped)!=0u||slow;
        assignment.blockedSeconds=blocked?assignment.blockedSeconds+dt:0.f;
        if(elapsed>=attackData.end||assignment.blockedSeconds>=.5f||domePastExit(attackData,assignment,value,position))
            domeComplete(assignment,route);
    }
    member.position=float4(position,0.f);
    bool carry=assigned&&hasAttack&&assignment.phase==DomeAttack&&nearPlayer(position,tick.playerCount,true);
    member.velocity=float4(velocity,carry?-4.f:0.f);
    member.spatialSteering=float4(queryDirection,queryMotion?cap:0.f);
}


[numthreads(128,1,1)]
void advanceStorm(uint index:SV_DispatchThreadID) {
    StormTick tick=tickInput[0];
    if(index>=tick.population)return;
    StormStepMember member=members[index];
    if(member.identity!=tick.identity||member.state==StormAbsent||member.state==StormDead
            ||member.birthSequence==tick.sequence)return;
    StormDomeMember assignment=(StormDomeMember)0;
    StormRoute route=(StormRoute)0;
    if(hasRegional) {
        assignment=assignments[index];
        if(assignment.identity==tick.identity)route=routes[index];
    }
    stepMember(index,tick,member,assignment,route);
    members[index]=member;
    if(assignment.identity==tick.identity) {
        assignments[index]=assignment;
        routes[index]=route;
    }
}
