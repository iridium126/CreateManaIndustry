void applyStormControlRow(StormTick tick,StormControl command,uint rank) {
    uint index=command.member+rank;
    uint id=uint(command.scope),slot=uint(command.scope>>32);
    if(command.op==CMI_RESOURCE_STORM_CONTROL_COLUMN_STATE){
        bool same=columns[slot].identity==tick.identity&&columns[slot].id==id;
        uint state=command.flags&3u;
        if(!state){if(same)columns[slot].state=0;return;}
        if(!same){
            if((command.flags&CMI_RESOURCE_STORM_DOME_CONTROL_CREATE)==0u)return;
            columns[slot]=(StormColumn)0;columns[slot].identity=tick.identity;columns[slot].id=id;
        }else if(!columns[slot].state)return;
        columns[slot].state=state;
        columns[slot].seed=command.member;
        columns[slot].landingTop=command.a;
        columns[slot].start=command.b.x;columns[slot].assemble=command.b.y;columns[slot].touch=command.b.z;columns[slot].end=command.b.w;
        return;
    }
    if(command.op==CMI_RESOURCE_STORM_CONTROL_COLUMN_RESTORE||command.op==CMI_RESOURCE_STORM_CONTROL_COLUMN_RESTORE_ROW){
        if(columns[slot].identity!=tick.identity||columns[slot].id!=id)return;
        if(command.op==CMI_RESOURCE_STORM_CONTROL_COLUMN_RESTORE){
            if(rank==0u&&columns[slot].state!=0u){
                columns[slot].restoreToken=command.member;
                columns[slot].restoreElapsed=command.a.x;
                columns[slot].state=1u;
            }
            restores[slot*columnMembers+rank].identity=tick.identity;restores[slot*columnMembers+rank].id=id;restores[slot*columnMembers+rank].token=command.member;restores[slot*columnMembers+rank].parts=0u;
        }else if(columns[slot].state!=0u){
            uint record=command.member/CMI_RESOURCE_STORM_COLUMN_RESTORE_PARTS;
            uint part=command.member%CMI_RESOURCE_STORM_COLUMN_RESTORE_PARTS;
            if(restores[slot*columnMembers+record].identity!=tick.identity||restores[slot*columnMembers+record].id!=id||restores[slot*columnMembers+record].token!=columns[slot].restoreToken)return;
            restores[slot*columnMembers+record].rows[part*2u]=command.a;restores[slot*columnMembers+record].rows[part*2u+1u]=command.b;
            InterlockedOr(restores[slot*columnMembers+record].parts,1u<<part);
        }
        return;
    }
    if(command.op==CMI_RESOURCE_STORM_CONTROL_DOME_STATE||command.op==CMI_RESOURCE_STORM_CONTROL_HELIX_STATE){
        bool helix=command.op==CMI_RESOURCE_STORM_CONTROL_HELIX_STATE;
        uint kind=helix?RegionalHelix:RegionalDome;
        uint phase=command.flags&3u;
        bool same=attacks[slot].identity==tick.identity&&attacks[slot].id==id&&attacks[slot].kind==kind;
        if(phase==0){
            if(same)attacks[slot].state=0;
            return;
        }
        if(!same){
            if((command.flags&CMI_RESOURCE_STORM_DOME_CONTROL_CREATE)==0)return;
            attacks[slot]=(StormDome)0;attacks[slot].identity=tick.identity;attacks[slot].id=id;attacks[slot].kind=kind;
            if(helix)helixes[slot]=(StormHelix)0;
            for(uint flow=0;flow<domeFlows;++flow)attacks[slot].paused[flow]=asfloat(0x7f800000u);
        }else if(attacks[slot].state==0||phase<attacks[slot].state)return;
        uint previousState=attacks[slot].state;
        attacks[slot].state=phase;
        if(helix){
            attacks[slot].start=command.a.x;
            attacks[slot].lockedAt=command.a.y;
            attacks[slot].end=command.a.z;
            helixes[slot].freeze=command.a.w;
            helixes[slot].speed=command.b.x;
            helixes[slot].cap=command.b.y;
            helixes[slot].targetArc=command.b.z;
            helixes[slot].terrainReady=command.b.w>.5f;
            helixes[slot].count=command.member;
            if(phase==2u)helixes[slot].recovery[0]=(float4)0;
            return;
        }
        attacks[slot].centerYaw=command.a;
        attacks[slot].start=command.b.x;
        if(previousState!=2u||attacks[slot].state!=2u)
            attacks[slot].lockedAt=previousState==1u&&attacks[slot].state==2u?domeElapsed(tick,attacks[slot].start):command.b.y;
        attacks[slot].end=command.b.z;
        attacks[slot].radius=command.b.w;
        return;
    }
    if(command.op==CMI_RESOURCE_STORM_CONTROL_HELIX_FRAME||command.op==CMI_RESOURCE_STORM_CONTROL_HELIX_RECOVERY){

        if(attacks[slot].identity!=tick.identity||attacks[slot].id!=id||attacks[slot].kind!=RegionalHelix||!attacks[slot].state)return;
        if(command.op==CMI_RESOURCE_STORM_CONTROL_HELIX_FRAME){
            uint frame=command.member/4u,row=(command.member%4u)*2u;
            if(frame>=helixes[slot].count)return;
            helixes[slot].frames[frame][row]=command.a;
            helixes[slot].frames[frame][row+1u]=command.b;
        }else if(attacks[slot].state!=2u){
            helixes[slot].recovery[command.member]=command.a;
            if(command.member+1<CMI_RESOURCE_STORM_HELIX_RECOVERY_ROWS)helixes[slot].recovery[command.member+1]=command.b;
        }
        return;
    }
    if(command.op==CMI_RESOURCE_STORM_CONTROL_DOME_PAUSE){
        if(attacks[slot].identity==tick.identity&&attacks[slot].id==id&&attacks[slot].kind==RegionalDome)
            attacks[slot].paused[command.flags]=min(attacks[slot].paused[command.flags],command.a.x);
        return;
    }
    if(command.op==CMI_RESOURCE_STORM_CONTROL_DOME_ASSIGN||command.op==CMI_RESOURCE_STORM_CONTROL_HELIX_ASSIGN){

        bool helix=command.op==CMI_RESOURCE_STORM_CONTROL_HELIX_ASSIGN;
        if(attacks[slot].identity!=tick.identity||attacks[slot].id!=id||!attacks[slot].state
            ||attacks[slot].kind!=(helix?RegionalHelix:RegionalDome))return;
        if(assignments[index].identity==tick.identity&&assignments[index].id==id&&assignments[index].slot==slot&&assignments[index].rank==command.flags)return;
        assignments[index]=(StormDomeMember)0;
        assignments[index].identity=tick.identity;assignments[index].id=id;assignments[index].slot=slot;assignments[index].rank=command.flags;
        assignments[index].kind=attacks[slot].kind;
        assignments[index].phase=DomeGather;
        if(helix)assignments[index].helixLocal=command.a;
        assignments[index].pauseSeen=asfloat(0x7f800000u);
        routes[index]=(StormRoute)0;
        return;
    }
    if(command.op==CMI_RESOURCE_STORM_CONTROL_COLUMN_ASSIGN){
        if(columns[slot].identity!=tick.identity||columns[slot].id!=id||!columns[slot].state)return;
        columns[slot].roster[command.flags]=index+1u;
        if(assignments[index].identity==tick.identity&&assignments[index].kind==RegionalColumn&&assignments[index].id==id
            &&assignments[index].slot==slot&&assignments[index].rank==command.flags)return;
        assignments[index]=(StormDomeMember)0;assignments[index].identity=tick.identity;assignments[index].kind=RegionalColumn;
        assignments[index].id=id;assignments[index].slot=slot;assignments[index].rank=command.flags;assignments[index].phase=DomeGather;
        routes[index]=(StormRoute)0;
        return;
    }
    if(command.op==CMI_RESOURCE_STORM_CONTROL_DOME_RELEASE||command.op==CMI_RESOURCE_STORM_CONTROL_COLUMN_RELEASE){
        bool columnRelease=command.op==CMI_RESOURCE_STORM_CONTROL_COLUMN_RELEASE;
        if(assignments[index].identity==tick.identity&&assignments[index].id==id&&assignments[index].slot==slot
            &&columnRelease==(assignments[index].kind==RegionalColumn)
            &&assignments[index].phase!=DomeReturn&&assignments[index].phase!=DomeHome){
            assignments[index].phase=DomeReleasePending;
        }
        return;
    }
    if(command.op==CMI_RESOURCE_STORM_CONTROL_SPAWN) {
        // A previously confirmed death can precede the local spawn budget.
        if(members[index].identity==tick.identity&&members[index].state!=StormAbsent)return;
        StormStepMember born=(StormStepMember)0;
        born.identity=tick.identity;born.birthSequence=tick.sequence;born.state=StormAlive;
        born.seed=cmiStormMemberSeed(float(tick.seed),index);born.hp=20.f;
        if(members[index].identity==tick.identity){
            born.correctionPositionTime=members[index].correctionPositionTime;
            born.correctionVelocityStrength=members[index].correctionVelocityStrength;
            born.outerTarget=members[index].outerTarget;
            born.outerCycle=members[index].outerCycle;
            born.outerState=members[index].outerState;
        }
        vec3 pointOnOrbit=0,motion=0,position=0,velocity=0;
        home(tick,born.seed,pointOnOrbit,motion);
        cmiStormBirth(index,(command.flags&1u)!=0,tick.radius,tick.speed,center(tick),
            pointOnOrbit,motion,position,velocity);
        born.previous=born.position=float4(position,0.f);born.velocity=float4(velocity,0.f);
        born.spatialSteering=float4(velocity,tick.speed);
        members[index]=born;
    } else if(command.op==CMI_RESOURCE_STORM_CONTROL_DAMAGE) {
        bool died=(command.flags&1u)!=0;
        if(members[index].identity!=tick.identity||members[index].state==StormAbsent) {
            if(died){StormStepMember dead=(StormStepMember)0;dead.identity=tick.identity;dead.state=StormDead;members[index]=dead;}
            return;
        }
        if(members[index].state!=StormAlive)return;
        // The server's death flag controls lifecycle, including when its
        // damage history differs from the local HP history.
        members[index].hp=died?0.f:max(.0001f,members[index].hp-command.a.x);
        members[index].hurt=1.f;members[index].damageLight=command.a.w;
        members[index].velocity.x=members[index].velocity.x*.5f-command.a.y;
        members[index].velocity.z=members[index].velocity.z*.5f-command.a.z;
        if(died)members[index].state=StormCorpse;
    } else if(command.op==CMI_RESOURCE_STORM_CONTROL_CORRECTION) {
        if(members[index].identity!=tick.identity){
            members[index]=(StormStepMember)0;
            members[index].identity=tick.identity;
        }
        if(members[index].state==StormDead)return;
        members[index].correctionPositionTime=command.a;members[index].correctionVelocityStrength=command.b;
    } else if(command.op==CMI_RESOURCE_STORM_CONTROL_OUTER_START){
        if(members[index].identity!=tick.identity){members[index]=(StormStepMember)0;members[index].identity=tick.identity;}
        if(members[index].state==StormCorpse||members[index].state==StormDead||command.flags<=members[index].outerCycle)return;
        members[index].outerTarget=command.a;members[index].outerCycle=command.flags;members[index].outerState=0;
        assignments[index]=(StormDomeMember)0;
    } else if(command.op==CMI_RESOURCE_STORM_CONTROL_OUTER_RELEASE&&members[index].identity==tick.identity
        &&members[index].outerCycle==command.flags){
        members[index].outerState=outerArrived|outerPublished;
    }
}


[numthreads(128,1,1)]
void applyControls(uint rank:SV_DispatchThreadID) {
    StormTick tick=tickInput[0];
    if(commandMode==0u) {
        StormControl command=controls[commandFirst];
        if(rank<command.span)applyStormControlRow(tick,command,rank);
    } else if(rank<commandCount) {
        applyStormControlRow(tick,controls[commandFirst+rank],0);
    }
}
