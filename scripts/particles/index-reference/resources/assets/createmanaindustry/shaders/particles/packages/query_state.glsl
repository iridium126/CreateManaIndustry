#pragma cmi_include packages/body_lifecycle.glsl
struct QueryBody { vec4 positionMass; vec4 velocityGround; vec4 extentYaw; vec4 previousSleep; };
struct QueryChain { vec4 startRadius; vec4 endLength; vec4 progress; vec4 targetYaw; };
struct QueryMeta { uvec4 identity; uvec4 selection; vec4 previousTargetYaw; vec4 style; vec4 nudge; };
struct QueryResult { uvec4 identity; uvec4 selection; vec4 positionYaw; vec4 motionState; vec4 targetYaw; vec4 progress; vec4 previousPositionYaw; vec4 previousTargetYaw; };
layout(std430,binding=0) readonly buffer QueryBodies { QueryBody bodies[]; };
layout(std430,binding=1) readonly buffer QueryChains { QueryChain chains[]; };
layout(std430,binding=2) readonly buffer QueryMetadata { QueryMeta meta[]; };
layout(std430,binding=3) readonly buffer QueryAdmission { uvec4 admitted[]; };
layout(std430,binding=6) readonly buffer QueryHistory { vec4 history[]; };
uniform uint uCount,uBodyCount,uPoolCapacity;
const uint QUERY_NONE=0xffffffffu;
QueryResult queryEmpty() { return QueryResult(uvec4(0),uvec4(0),vec4(0),vec4(0),vec4(0),vec4(0),vec4(0),vec4(0)); }
bool queryIdentity(uint candidate) {
    return candidate<uCount && all(equal(meta[candidate].identity,admitted[2u*candidate]))
            && meta[candidate].selection.x<uBodyCount;
}
bool queryVisible(uint candidate) {
    if(!queryIdentity(candidate))return false;
    uvec4 a=admitted[2u*candidate+1u];
    float state=bodies[meta[candidate].selection.x].previousSleep.w;
    return a.x>0u && a.x<=uPoolCapacity && (a.y&4u)==0u && (state>=0.0 || state==PACKAGE_COLLISION_FROZEN);
}
QueryResult queryPose(uint candidate,bool retired) {
    QueryMeta m=meta[candidate];uint body=m.selection.x;QueryBody b=bodies[body];
    uint flags=retired?m.selection.w:admitted[2u*candidate+1u].y;
    QueryResult result=QueryResult(m.identity,uvec4(candidate,body,flags,QUERY_NONE),
            vec4(b.positionMass.xyz,b.extentYaw.w),vec4(b.velocityGround.xyz,b.previousSleep.w),
            vec4(b.positionMass.xyz,b.extentYaw.w),vec4(0),history[2u*body],history[2u*body+1u]);
    if((flags&1u)!=0u) {
        QueryChain c=chains[body];
        if(c.progress.z<2.0 || c.startRadius.w<0.0 || c.startRadius.w>=131072.0 || c.startRadius.w!=floor(c.startRadius.w))return queryEmpty();
        result.targetYaw=c.targetYaw;
        result.selection.w=uint(c.startRadius.w);result.progress=vec4(c.startRadius.x,c.progress.x,c.progress.y,c.progress.w);
    } else result.progress=vec4(0,0,b.velocityGround.w,b.extentYaw.y);
    return result;
}
