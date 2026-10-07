#ifdef CMI_MOVING_PREPARE
layout(std430,binding=9) buffer MovingPose { uvec4 movingPose[16]; };
#else
layout(std430,binding=9) readonly buffer MovingPose { uvec4 movingPose[16]; };
#endif
struct MovingNode {vec4 lo;vec4 hi;uvec4 material;};
layout(std430,binding=10) readonly buffer MovingGeometry {MovingNode movingNodes[];};
uniform bool uMovingReady;
vec3 mv(uint at){return uintBitsToFloat(movingPose[at].xyz);}
mat3 rawRotation(bool old){uint p=old?0u:4u;return mat3(mv(p),mv(p+1u),mv(p+2u));}
vec3 movingScale(bool old){mat3 r=rawRotation(old);return vec3(length(r[0]),length(r[1]),length(r[2]));}
vec3 movingTranslation(bool old){return mv(old?3u:7u);}
uint movingIdentity(){return movingPose[8].w;}
bool movingGeometryReady(){return movingPose[9].w!=0u;}
uint movingNodeCount(){return movingPose[15].x;}
ivec4 movingGeometryFailureDetail(){return ivec4(movingIdentity(),movingPose[15].yzw);}
vec4 quaternion(mat3 r) {
    float trace=r[0][0]+r[1][1]+r[2][2];vec4 q;
    if(trace>0.0){float s=sqrt(trace+1.0)*2.0;q=vec4((r[1][2]-r[2][1])/s,(r[2][0]-r[0][2])/s,(r[0][1]-r[1][0])/s,.25*s);}
    else if(r[0][0]>r[1][1]&&r[0][0]>r[2][2]){float s=sqrt(1.0+r[0][0]-r[1][1]-r[2][2])*2.0;q=vec4(.25*s,(r[1][0]+r[0][1])/s,(r[2][0]+r[0][2])/s,(r[1][2]-r[2][1])/s);}
    else if(r[1][1]>r[2][2]){float s=sqrt(1.0+r[1][1]-r[0][0]-r[2][2])*2.0;q=vec4((r[1][0]+r[0][1])/s,.25*s,(r[2][1]+r[1][2])/s,(r[2][0]-r[0][2])/s);}
    else{float s=sqrt(1.0+r[2][2]-r[0][0]-r[1][1])*2.0;q=vec4((r[2][0]+r[0][2])/s,(r[2][1]+r[1][2])/s,.25*s,(r[0][1]-r[1][0])/s);}
    return normalize(q);
}
mat3 quaternionMatrix(vec4 q) {
    vec3 v=q.xyz;float w=q.w;
    return mat3(1.-2.*(v.y*v.y+v.z*v.z),2.*(v.x*v.y+v.z*w),2.*(v.x*v.z-v.y*w),
                2.*(v.x*v.y-v.z*w),1.-2.*(v.x*v.x+v.z*v.z),2.*(v.y*v.z+v.x*w),
                2.*(v.x*v.z+v.y*w),2.*(v.y*v.z-v.x*w),1.-2.*(v.x*v.x+v.y*v.y));
}
mat3 movingRotation(float t) {
    vec4 a=uintBitsToFloat(movingPose[10]),b=uintBitsToFloat(movingPose[11]);
    float cosine=clamp(dot(a,b),-1.0,1.0);
    vec4 q;
    if(cosine>.9995)q=normalize(mix(a,b,t));
    else {float angle=acos(cosine);q=(a*sin((1.-t)*angle)+b*sin(t*angle))/sin(angle);}
    return quaternionMatrix(q);
}
vec3 localPoint(vec3 point,bool old){mat3 r=rawRotation(old);vec3 s=movingScale(old);r[0]/=s.x;r[1]/=s.y;r[2]/=s.z;return transpose(r)*(point-movingTranslation(old))/s;}
vec3 projectPoint(vec3 point,bool old){return rawRotation(old)*point+movingTranslation(old);}
bool movingCoarse(vec3 lo,vec3 hi){return all(lessThanEqual(lo,mv(13u)))&&all(greaterThanEqual(hi,mv(12u)));}
bool satAxis(vec3 axis,vec3 delta,vec3 bodyExtent,mat3 r,vec3 boxExtent,inout float gap,inout vec3 normal) {
    float squared=dot(axis,axis);if(squared<1e-10)return false;
    axis*=inversesqrt(squared);float side=dot(delta,axis);
    float distance=abs(side)-dot(abs(axis),bodyExtent)-dot(abs(transpose(r)*axis),boxExtent);
    if(distance>gap){gap=distance;normal=axis*(side<0.0?-1.0:1.0);}return distance>0.0;
}
// Full 15-axis AABB/OBB SAT, including edge/edge separation.
float movingGap(vec3 point,vec3 extent,MovingNode box,float t,out vec3 normal) {
    mat3 r=movingRotation(t);vec3 scale=mix(movingScale(true),movingScale(false),t);
    vec3 centre=(box.lo.xyz+box.hi.xyz)*.5,boxHalf=(box.hi.xyz-box.lo.xyz)*.5*scale;
    vec3 delta=point-(mix(movingTranslation(true),movingTranslation(false),t)+r*(centre*scale));
    float gap=-1e30;normal=vec3(0,1,0);
    satAxis(vec3(0,1,0),delta,extent,r,boxHalf,gap,normal);satAxis(vec3(1,0,0),delta,extent,r,boxHalf,gap,normal);satAxis(vec3(0,0,1),delta,extent,r,boxHalf,gap,normal);
    for(int j=0;j<3;j++)satAxis(r[j],delta,extent,r,boxHalf,gap,normal);
    for(int i=0;i<3;i++)for(int j=0;j<3;j++){vec3 axis=vec3(0);axis[i]=1.;satAxis(cross(axis,r[j]),delta,extent,r,boxHalf,gap,normal);}
    return gap;
}
vec3 movingVelocity(vec3 globalPoint){vec3 local=localPoint(globalPoint,false);return (globalPoint-projectPoint(local,true))*20.0;}
// Rotation of an OBB face changes the projection of the body relative to the
// rotation axis. A long remote corner of the box is irrelevant to that face's
// local separation; using its radius can stall grazing bodies indefinitely.
vec4 movingTurn() {
    vec4 qa=uintBitsToFloat(movingPose[10]),qb=uintBitsToFloat(movingPose[11]);
    if(all(equal(qa,qb)))return vec4(0);
    // qb * conjugate(qa): the fixed world-space axis of the slerp/nlerp arc.
    vec3 axis=qa.w*qb.xyz-qb.w*qa.xyz+cross(qa.xyz,qb.xyz);
    float sine=length(axis);
    if(sine<1e-8)return vec4(0);
    return vec4(axis/sine,2.*atan(sine,max(0.,dot(qa,qb))));
}
float movingBodyTurnRadius(vec3 initial,vec3 motion,vec3 extent,vec3 axis) {
    vec3 a=initial-movingTranslation(true),b=initial+motion-movingTranslation(false);
    if(dot(axis,axis)<.5)return max(length(a),length(b))+length(extent);
    return max(length(cross(axis,a)),length(cross(axis,b)))+length(extent);
}
// Each separating OBB face proves a collision-free time interval. Taking the
// largest proven interval is conservative: all axes must overlap for a hit.
float movingSafeInterval(float gap,float closing,float curvature) {
    if(curvature<1e-8)return gap/max(closing,1e-8);
    float root=sqrt(closing*closing+2.*curvature*gap);
    return closing>=0.?2.*gap/(closing+root):(root-closing)/curvature;
}
float movingFaceAdvance(vec3 initial,vec3 motion,vec3 extent,MovingNode box,float t,float fallback) {
    mat3 r=movingRotation(t);vec3 sa=movingScale(true),sb=movingScale(false),scale=mix(sa,sb,t);
    vec3 centre=(box.lo.xyz+box.hi.xyz)*.5,halfExtent=(box.hi.xyz-box.lo.xyz)*.5;
    vec3 relative=initial+motion*t-mix(movingTranslation(true),movingTranslation(false),t);
    vec3 relativeMotion=motion-(movingTranslation(false)-movingTranslation(true));
    vec4 turn=movingTurn();vec3 angularVelocity=turn.xyz*turn.w;
    float angle=max(uintBitsToFloat(movingPose[14].x),turn.w*1.001);
    float radius=movingBodyTurnRadius(initial,motion,extent,turn.xyz);
    // Bound separation locally: g(t+h) >= gap - closing*h - curvature*h*h/2.
    // Charging the whole remaining frame's derivative variation to each tiny
    // advance stalls a clear grazing path near its minimum separation.
    float rateVariation=max(0.,angle-turn.w)*radius;
    float curvature=2.*angle*length(relativeMotion)+angle*angle*radius;
    float advance=fallback;
    for(int j=0;j<3;j++) {
        float side=dot(relative,r[j])-centre[j]*scale[j];
        float gap=abs(side)-dot(abs(r[j]),extent)-halfExtent[j]*scale[j];
        if(gap<=0.)continue;
        float direction=side<0.?-1.:1.;vec3 normal=r[j]*direction;
        vec3 derivative=cross(angularVelocity,normal);
        float supportClosing=0.;
        for(int k=0;k<3;k++) {
            // A component that cannot cross zero has a signed support derivative.
            // At an abs() cusp retain the upper derivative; never skip a hit.
            float slope=abs(normal[k])>angle*(1.-t)?sign(normal[k])*derivative[k]:abs(derivative[k]);
            supportClosing+=extent[k]*slope;
        }
        float closing=-dot(relativeMotion,normal)-dot(derivative,relative)
                +direction*centre[j]*(sb[j]-sa[j])+abs(sb[j]-sa[j])*halfExtent[j]
                +supportClosing+rateVariation;
        advance=max(advance,movingSafeInterval(gap,closing,curvature));
    }
    return advance;
}
// The SAT normal may be a world face or edge/edge axis while all three OBB
// face gaps are negative. Freeze that normal in world space and prove that
// every transformed corner remains behind the body's separating plane.
float movingAxisAdvance(vec3 initial,vec3 motion,vec3 extent,MovingNode box,float t,vec3 normal) {
    mat3 r=movingRotation(t);vec3 sa=movingScale(true),sb=movingScale(false),scale=mix(sa,sb,t);
    vec3 relative=initial+motion*t-mix(movingTranslation(true),movingTranslation(false),t);
    vec3 relativeMotion=motion-(movingTranslation(false)-movingTranslation(true));
    vec4 turn=movingTurn();vec3 angularVelocity=turn.xyz*turn.w;
    float angle=max(uintBitsToFloat(movingPose[14].x),turn.w*1.001);
    float plane=dot(relative,normal)-dot(abs(normal),extent),advance=1e30;
    for(int corner=0;corner<8;corner++) {
        vec3 local=vec3((corner&1)==0?box.lo.x:box.hi.x,(corner&2)==0?box.lo.y:box.hi.y,(corner&4)==0?box.lo.z:box.hi.z);
        vec3 vertex=r*(local*scale);float gap=plane-dot(vertex,normal);
        if(gap<=0.)return 0.;
        vec3 scaleMotion=r*(local*(sb-sa));
        float radius=max(length(cross(turn.xyz,r*(local*sa))),length(cross(turn.xyz,r*(local*sb))));
        if(dot(turn.xyz,turn.xyz)<.5)radius=max(length(local*sa),length(local*sb));
        float closing=dot(cross(angularVelocity,vertex)+scaleMotion-relativeMotion,normal)
                +max(0.,angle-turn.w)*radius;
        float curvature=angle*angle*radius+2.*angle*length(scaleMotion);
        advance=min(advance,movingSafeInterval(gap,closing,curvature));
    }
    return advance;
}
float movingConservativeAdvance(vec3 initial,vec3 motion,vec3 extent,MovingNode box,float t,vec3 normal,float fallback) {
    float advance=movingFaceAdvance(initial,motion,extent,box,t,fallback);
    if(t+advance<1.)advance=max(advance,movingAxisAdvance(initial,motion,extent,box,t,normal));
    return advance;
}
bool linearAxis(vec3 axis,vec3 delta,vec3 motion,vec3 extent,mat3 r,vec3 halfExtent,
                inout float enter,inout float exit,inout vec3 normal) {
    float squared=dot(axis,axis);if(squared<1e-10)return true;axis*=inversesqrt(squared);
    float side=dot(delta,axis),travel=dot(motion,axis);
    float radius=dot(abs(axis),extent)+dot(abs(transpose(r)*axis),halfExtent);
    if(abs(travel)<1e-8)return abs(side)<=radius+1e-6;
    float a=(-radius-side)/travel,b=(radius-side)/travel;
    float first=min(a,b),last=max(a,b);
    if(first>enter){enter=first;normal=axis*(travel>0.?-1.:1.);}exit=min(exit,last);
    return enter<=exit;
}
// Exact swept SAT intervals for constant orientation and scale. In particular,
// tangential movement across adjacent floor boxes cannot exhaust CCD iterations.
bool movingLinearSweep(vec3 initial,vec3 motion,vec3 extent,MovingNode box,out float enter,out vec3 normal) {
    mat3 r=movingRotation(0.);vec3 scale=movingScale(true),centre=(box.lo.xyz+box.hi.xyz)*.5;
    vec3 halfExtent=(box.hi.xyz-box.lo.xyz)*.5*scale;
    vec3 delta=initial-(movingTranslation(true)+r*(centre*scale));
    vec3 relative=motion-(movingTranslation(false)-movingTranslation(true));float exit=1.;enter=0.;
    movingGap(initial,extent,box,0.,normal);
    if(!linearAxis(vec3(0,1,0),delta,relative,extent,r,halfExtent,enter,exit,normal))return false;
    if(!linearAxis(vec3(1,0,0),delta,relative,extent,r,halfExtent,enter,exit,normal))return false;
    if(!linearAxis(vec3(0,0,1),delta,relative,extent,r,halfExtent,enter,exit,normal))return false;
    for(int j=0;j<3;j++)if(!linearAxis(r[j],delta,relative,extent,r,halfExtent,enter,exit,normal))return false;
    for(int i=0;i<3;i++)for(int j=0;j<3;j++){vec3 axis=vec3(0);axis[i]=1.;if(!linearAxis(cross(axis,r[j]),delta,relative,extent,r,halfExtent,enter,exit,normal))return false;}
    return enter<=1.&&exit>=0.;
}
