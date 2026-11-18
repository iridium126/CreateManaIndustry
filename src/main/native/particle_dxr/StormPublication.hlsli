uint2 split64(uint64_t value) { return uint2(uint(value),uint(value>>32u)); }

[numthreads(128,1,1)]
void prepareFeedback(uint3 group:SV_GroupID,uint lane:SV_GroupIndex) {
    StormTick tick=tickInput[0];
    uint slot=group.x;
    if(slot==0&&lane==0) {
        attempts[0]=0;
        attempts[1]=0;
        batches.Store(CMI_RESOURCE_STORM_OUTER_BATCH_OFFSET,0);
        batches.Store(CMI_RESOURCE_STORM_POSITION_BATCH_OFFSET,0);
        if(featherEnabled)feathers.Store(0,0);
        if(publishDisplay) {
            snapshot.Store4(0,uint4(CMI_RESOURCE_STORM_DISPLAY_MAGIC,CMI_RESOURCE_ABI,0,capacity));
            snapshot.Store4(16,uint4(split64(tick.sequence),split64(tick.tick)));
            snapshot.Store4(32,uint4(split64(tick.identity),0,0));
            snapshot.Store4(48,0);
        }
    }
    if(!hasRegional)return;
    if(slot==0&&lane<columnSlots) {
        uint offset=columnBatchOffset(lane);
        StormColumn column=columns[lane];
        bool activeColumn=column.identity==tick.identity&&column.state!=0;
        batches.Store4(offset,uint4(activeColumn?column.id:0u,activeColumn?column.restoreToken:0u,0,0));
        [unroll] for(uint row=1;row<5;row++)batches.Store4(offset+row*16,0);
    }
    StormDome attack=attacks[slot];
    if(lane==0) {
        uint id=attack.identity==tick.identity&&attack.state!=0?attack.id:0u;
        batches.Store(slot*4u,id);
        batches.Store(CMI_RESOURCE_STORM_DOME_BLOCKED_OFFSET+slot*4u,0);
        batches.Store(CMI_RESOURCE_STORM_DOME_UNKNOWN_OFFSET+slot*4u,0);
        batches.Store4(domeContactOffset(slot),0);
        batches.Store4(domeContactOffset(slot)+16,0);
        uint4 reference=0;
        if(id!=0&&attack.kind==RegionalHelix) {
            StormHelix helix=helixes[slot];
            float elapsed=domeElapsed(tick,attack.start);
            HelixReference current=helixReference(attack,helix,elapsed);
            helixes[slot].current=packHelix(current);
            helixes[slot].previous=packHelix(helixReference(attack,helix,elapsed-dt));
            reference=uint4(current.version,current.valid?1u:0u,asuint(current.seconds),0);
        }
        batches.Store4(CMI_RESOURCE_STORM_HELIX_REFERENCE_OFFSET+slot*16u,reference);
    }
    for(uint word=lane;word<domeBitmapWords;word+=128u) {
        batches.Store(domeBitmapOffset(CMI_RESOURCE_STORM_DOME_READY_OFFSET,slot,word),0);
        batches.Store(domeBitmapOffset(CMI_RESOURCE_STORM_DOME_COMPLETED_OFFSET,slot,word),0);
    }
    if(lane<domeFlows) {
        uint flow=slot*domeFlows+lane;
        if(attack.kind==RegionalDome) {
            domePauseBits[flow]=asuint(attack.paused[lane]);
        } else {
            // Helix receipts carry no Dome pauses, including when a slot is reused.
            batches.Store(CMI_RESOURCE_STORM_DOME_PAUSED_OFFSET+flow*4u,0x7f800000u);
        }
    }
}

groupshared uint changedFlows[domeFlows];
[numthreads(128,1,1)]
void finishDome(uint3 group:SV_GroupID,uint lane:SV_GroupIndex) {
    uint slot=group.x;
    if(attacks[slot].kind!=RegionalDome)return;
    if(lane<domeFlows) {
        uint base=slot*domeFlows+lane;
        uint bits=domePauseBits[base];
        float pause=asfloat(bits);
        changedFlows[lane]=pause<attacks[slot].paused[lane];
        attacks[slot].paused[lane]=pause;
        batches.Store(CMI_RESOURCE_STORM_DOME_PAUSED_OFFSET+base*4u,bits);
    }
    GroupMemoryBarrierWithGroupSync();
    for(uint word=lane;word<domeBitmapWords;word+=128u) {
        uint offset=domeBitmapOffset(CMI_RESOURCE_STORM_DOME_READY_OFFSET,slot,word);
        uint bits=batches.Load(offset);
        for(uint pending=bits;pending;pending&=pending-1u) {
            uint bit=firstbitlow(pending);
            if(changedFlows[domeFlowsDevice[word*32u+bit]])bits&=~(1u<<bit);
        }
        batches.Store(offset,bits);
    }
}

[numthreads(128,1,1)]
void clearFeathers(uint index:SV_DispatchThreadID) {
    if(index<featherImpactLimit)featherCells[index].state=0;
}

[numthreads(128,1,1)]
void compactFeathers(uint index:SV_DispatchThreadID) {
    if(index>=featherImpactLimit||featherCells[index].state!=2)return;
    uint row;
    feathers.InterlockedAdd(0,1,row);
    feathers.Store4(16u+row*16u,asuint(featherCells[index].impact));
}

[numthreads(128,1,1)]
void publishPositions(uint index:SV_DispatchThreadID) {
    StormTick tick=tickInput[0];
    if(index>=tick.population)return;
    StormStepMember member=members[index];
    if(member.identity!=tick.identity||member.state!=StormAlive)return;
    if(!nearPlayer(member.position.xyz,tick.playerCount,false))return;
    uint row;
    batches.InterlockedAdd(CMI_RESOURCE_STORM_POSITION_BATCH_OFFSET,1u,row);
    if(row>=CMI_RESOURCE_STORM_POSITION_BATCH_LIMIT)return;
    uint offset=CMI_RESOURCE_STORM_POSITION_BATCH_OFFSET+16u+row*CMI_RESOURCE_STORM_POSITION_ROW_BYTES;
    vec3 relative=member.position.xyz-center(tick);
    batches.Store4(offset,asuint(float4(float(index),0.f,relative.xy)));
    batches.Store4(offset+16,asuint(float4(relative.z,member.velocity.xyz)));
}

float absoluteColumnTime(float start,float elapsed) {
    float result=start+elapsed;
    if(result<0.f)return result+104857.6f;
    return result>=104857.6f?result-104857.6f:result;
}

[numthreads(64,1,1)]
void captureColumns(uint3 group:SV_GroupID,uint rank:SV_GroupIndex) {
    StormTick tick=tickInput[0];
    uint4 request=tick.columnCaptures[group.x];
    uint offset=CMI_RESOURCE_STORM_COLUMN_CAPTURE_OFFSET
        +(group.x*columnMembers+rank)*CMI_RESOURCE_STORM_COLUMN_CAPTURE_FLOATS*4u;
    batches.Store(offset,asuint(255.f));
    if(!hasRegional)return;
    StormColumn column=columns[request.x];
    if(column.identity!=tick.identity||column.id!=request.y||column.state!=1u||!column.roster[rank])return;
    uint index=column.roster[rank]-1u;
    StormStepMember member=members[index];
    StormColumnMember state=columnStates[request.x*columnMembers+rank];
    StormDomeMember assignment=assignments[index];
    if(member.identity!=tick.identity||member.state!=StormAlive||state.identity!=tick.identity
            ||state.id!=column.id||state.replaying||assignment.id!=column.id||assignment.kind!=RegionalColumn)return;
    vec3 origin=column.landingTop.xyz;
    batches.Store4(offset,asuint(float4(float(state.phase),member.position.xyz-origin)));
    batches.Store4(offset+16,asuint(float4(member.velocity.xyz,state.phase==0?0.f:absoluteColumnTime(column.start,state.descentStart))));
    batches.Store3(offset+32,asuint(float3(state.touchdown<0?-1.f:absoluteColumnTime(column.start,state.touchdown),
            state.release<0?-1.f:absoluteColumnTime(column.start,state.release),state.distance)));
    batches.Store3(offset+44,asuint(state.origin.xyz-origin));
    batches.Store3(offset+56,asuint(state.initialVelocity.xyz));
    batches.Store3(offset+68,asuint(state.phase==0?(float3)0:state.descentOrigin.xyz-origin));
    batches.Store3(offset+80,asuint(state.descentDirection.xyz));
    batches.Store3(offset+92,asuint(state.tangent.xyz));
    batches.Store4(offset+104,asuint(float4(state.bloomRadius,state.bloomAngle,0,0)));
}

[numthreads(128,1,1)]
void publishSnapshot(uint index:SV_DispatchThreadID) {
    StormTick tick=tickInput[0];
    StormStepMember member=(StormStepMember)0;
    uint64_t shown=tick.projectedIdentity==tick.identity?tick.projectedSequence:0;
    if(index<tick.population)member=members[index];
    bool poof=member.poofSequence>shown;
    bool visible=index<tick.population&&member.identity==tick.identity
        &&(member.state==StormAlive||member.state==StormCorpse||poof);
    uint prefix=WavePrefixCountBits(visible),count=WaveActiveCountBits(visible);
    uint base=0;
    if(count&&WaveIsFirstLane())snapshot.InterlockedAdd(8u,count,base);
    base=WaveReadLaneFirst(base);
    if(!visible)return;
    uint offset=CMI_RESOURCE_STORM_DISPLAY_HEADER_BYTES+(base+prefix)*CMI_RESOURCE_STORM_DISPLAY_RECORD_BYTES;
    uint attack=0,rank=0,flags=member.terrainState&15u;
    if(member.birthSequence==tick.sequence)flags|=CMI_RESOURCE_STORM_DISPLAY_BIRTH;
    if(member.state==StormAlive&&hasRegional) {
        StormDomeMember assignment=assignments[index];
        if(assignment.identity==tick.identity&&assignment.phase!=DomeHome) {
            attack=assignment.id;
            rank=assignment.rank;
            flags|=(assignment.phase<<CMI_RESOURCE_STORM_DISPLAY_PHASE_SHIFT)
                |(assignment.kind<<CMI_RESOURCE_STORM_DISPLAY_KIND_SHIFT)
                |(assignment.slot<<CMI_RESOURCE_STORM_DISPLAY_SLOT_SHIFT);
            if(assignment.kind==RegionalColumn) {
                snapshot.InterlockedOr(CMI_RESOURCE_STORM_DISPLAY_COLUMN_MASK_OFFSET+(assignment.slot>>5u)*4u,
                    1u<<(assignment.slot&31u));
                uint columnRank=assignment.slot*columnMembers+rank;
                StormColumnMember state=columnStates[columnRank];
                uint columnOffset=CMI_RESOURCE_STORM_DISPLAY_HEADER_BYTES+capacity*CMI_RESOURCE_STORM_DISPLAY_RECORD_BYTES
                    +columnRank*CMI_RESOURCE_STORM_DISPLAY_COLUMN_BYTES;
                bool curve=state.identity==tick.identity&&state.id==assignment.id&&!state.replaying
                    &&(member.terrainState&(StormTerrainClipped|StormDiscontinuity|StormEmbedded))==0
                    &&(state.phase==columnDescent||state.phase==columnBloom);
                snapshot.Store4(columnOffset+32u,uint4(curve?state.id:0u,state.phase,asuint(state.bloomAngle),0u));
                if(curve) {
                    snapshot.Store4(columnOffset,asuint(float4(state.descentOrigin.xyz,state.distance)));
                    snapshot.Store4(columnOffset+16,asuint(float4(state.descentDirection.xyz,state.bloomRadius)));
                    if(state.phase==columnDescent)for(uint i=0;i<CMI_RESOURCE_STORM_DISPLAY_COLUMN_ARC_NODES;i++)
                        snapshot.Store(columnOffset+CMI_RESOURCE_STORM_DISPLAY_COLUMN_ARC_OFFSET+i*4u,asuint(state.arc[i]));
                }
            }
        }
    }
    if(poof) {
        flags|=CMI_RESOURCE_STORM_DISPLAY_POOF;
        uint2 bits=split64(member.poofSequence);
        member.position.w=asfloat(bits.x);
        member.previous.w=asfloat(bits.y);
        member.velocity.w=member.damageLight;
    }
    snapshot.Store4(offset,asuint(member.position));
    snapshot.Store4(offset+16,asuint(member.previous));
    snapshot.Store4(offset+32,asuint(member.velocity));
    snapshot.Store4(offset+CMI_RESOURCE_STORM_DISPLAY_APPEARANCE_OFFSET,asuint(float4(member.hp,member.age,member.hurt,member.seed)));
    snapshot.Store4(offset+CMI_RESOURCE_STORM_DISPLAY_KEY_OFFSET,uint4(index,attack,rank,flags));
}

[numthreads(1,1,1)]
void sealTick() {
    StormTick tick=tickInput[0];
    receipt.Store4(0,uint4(split64(tick.sequence),split64(tick.tick)));
    receipt.Store3(16,uint3(split64(tick.identity),publishDisplay?snapshot.Load(8):0u));
    if(publishDisplay)snapshot.Store(CMI_RESOURCE_STORM_DISPLAY_READY_OFFSET,1u);
}
