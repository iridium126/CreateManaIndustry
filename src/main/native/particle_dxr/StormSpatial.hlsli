void storeInstance(uint index,float3 position,float3 half,uint mask,uint2 blas) {
    uint offset=index*64u;
    instances.Store4(offset,asuint(float4(half.x,0,0,position.x)));
    instances.Store4(offset+16u,asuint(float4(0,half.y,0,position.y)));
    instances.Store4(offset+32u,asuint(float4(0,0,half.z,position.z)));
    instances.Store4(offset+48u,uint4(index|(mask<<24u),0u,blas));
}

[numthreads(128,1,1)]
void prepareInstances(uint index:SV_DispatchThreadID) {
    StormTick tick=tickInput[0];
    if(index<tick.population) {
        StormStepMember member=members[index];
        bool alive=member.identity==tick.identity&&member.state==StormAlive;
        float3 motion=alive?member.velocity.xyz*.05f:0;
        storeInstance(index,alive?member.position.xyz+motion:0,.34f+abs(motion),alive?1u:0u,unitBlas);
    } else if(index==tick.population&&terrainBoxes) {

        storeInstance(index,0,1,1u,terrainBlas);
    }
}

static const float3 faceNormal[6] = {
    float3(0,0,-1),float3(0,0,1),float3(-1,0,0),
    float3(1,0,0),float3(0,-1,0),float3(0,1,0)
};
static const float circleCos[3]={1.f,-.5f,-.5f};
static const float circleSin[3]={0.f,.866025404f,-.866025404f};

[numthreads(128,1,1)]
void queryMotion(uint index:SV_DispatchThreadID) {
    StormTick tick=tickInput[0];
    if(index>=tick.population)return;
    StormStepMember member=members[index];
    float4 steering=member.spatialSteering;
    float speed=length(steering.xyz);
    if(member.identity!=tick.identity||member.state!=StormAlive||steering.w<=0||speed<.01f) {
        hints[index]=0;
        return;
    }
    float3 forward=steering.xyz/speed;
    float3 axis=abs(forward.y)<.9f?float3(0,1,0):float3(1,0,0);
    float3 right=normalize(cross(forward,axis)),up=cross(right,forward);
    float reach=max(.05f,min(speed,steering.w)*.1f+.34f),clearance=reach;
    float3 hint=0;
    for(uint probe=0;probe<simulationProbes;++probe) {
        uint side=simulationProbes==2?uint((tick.tick==0?0:tick.tick-1)%3u):max(1u,probe)-1u;
        RayDesc ray;
        ray.Origin=member.position.xyz;
        ray.Direction=probe==0?forward:forward*.906307787f+(right*circleCos[side]+up*circleSin[side])*.422618262f;
        ray.TMin=0.f;
        ray.TMax=reach;
        RayQuery<RAY_FLAG_FORCE_OPAQUE|RAY_FLAG_CULL_BACK_FACING_TRIANGLES|RAY_FLAG_SKIP_PROCEDURAL_PRIMITIVES> query;
        query.TraceRayInline(scene,RAY_FLAG_NONE,1u,ray);
        while(query.Proceed()) {}
        if(query.CommittedStatus()==COMMITTED_TRIANGLE_HIT) {
            float distance=query.CommittedRayT();
            hint+=faceNormal[query.CommittedPrimitiveIndex()%12u/2u]*(max(0.f,1.f-distance/reach)/simulationProbes);
            clearance=min(clearance,distance);
        }
    }
    hints[index]=float4(hint,clearance);
}
