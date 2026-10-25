// Exceptional swept contacts. One immutable candidate hash, one calculation,
// one application. No iterative position projection or penetration push.
layout(local_size_x=64) in;
layout(std430,binding=0) readonly buffer Before {vec4 data[];} beforePool;
layout(std430,binding=1) buffer Pool {vec4 data[];} pool;
layout(std430,binding=3) readonly buffer Counter {uint live;} counter;
layout(std430,binding=5) readonly buffer Emitters {vec4 u[];} emitters;
layout(std430,binding=13) readonly buffer Grid {int heads[GRID_TABLE];int next[];} grid;
layout(std430,binding=14) readonly buffer Previous {uint live;} previous;
layout(std430,binding=18) readonly buffer Players {vec4 pl[];} players;
layout(std430,binding=21) readonly buffer MemberMap {uint index[];} memberMap;
layout(std430,binding=27) readonly buffer OldState {uint words[];} starStateRead;
layout(std430,binding=28) buffer State {uint words[];} state;
#if CMI_DOME_PASS == 4
uniform uint uMode;
#else
const uint uMode=uint(CMI_DOME_PASS);
#endif
uniform float uDt;
uniform int uPlayerCount;
#define CMI_DOME_RUNTIME_FLOW
uint cmiDomeFlowWord(uint at){return CMI_STATE(starStateRead,at);}
#pragma cmi_include chunks/allay_storm.glsl
#pragma cmi_include chunks/dome_navigation.glsl
#pragma cmi_include chunks/storm_players.glsl
#pragma cmi_include chunks/storm_collision.glsl
#pragma cmi_include chunks/dome_contact_velocity.glsl
#pragma cmi_include chunks/dome_motion_budget.glsl
#pragma cmi_include chunks/dome_gather.glsl
#pragma cmi_include chunks/dome_path_state.glsl
#pragma cmi_include chunks/dome_path_segment.glsl
#define starStateWrite state
#pragma cmi_include chunks/dome_path_follow.glsl
#pragma cmi_include chunks/dome_path_cache.glsl
#undef starStateWrite
// Runtime swept proxy. The immutable reference has its own .50m proof.
const float CMI_BODY_RADIUS=.34,CMI_BODY_CELL=2.5;
int cmiDomeOwner(float identity){
 uint member=cmiStarMember(identity),a=cmiStarAssignment(identity);if(member>=131072u)return -1;
 if(a!=0u)return cmiStarSlot(a)<4u&&cmiDome(cmiStarBase(cmiStarSlot(a)))?int(cmiStarSlot(a)):-1;
 if((CMI_STATE(state,member*2u+1u)&255u)!=6u)return -1;uint id=CMI_STATE(state,member*2u);
 for(uint slot=0u;slot<4u;slot++)if(id!=0u&&id==uint(cmiStarVec(cmiStarBase(slot)).x+.5)&&cmiDome(cmiStarBase(slot)))return int(slot);return -1;
}
bool cmiDomeOld(uint member,out vec3 old){
 if(member>=uint(memberMap.index.length()))return false;uint at=memberMap.index[member];if(at==0u||at>previous.live||at*4u>uint(beforePool.data.length()))return false;
 vec4 p=beforePool.data[(at-1u)*4u];if(cmiStarMember(p.w)!=member)return false;old=p.xyz;return true;
}
float cmiDomeSweepFraction(vec3 delta,vec3 relative){
 float aa=dot(relative,relative),bb=dot(delta,relative),cc=dot(delta,delta)-CMI_BODY_RADIUS*CMI_BODY_RADIUS,disc=bb*bb-aa*cc;
 if(cc>=0.&&aa>.00000001&&bb<0.&&disc>=0.){float hit=(-bb-sqrt(disc))/aa;if(hit>=0.&&hit<=1.)return max(0.,hit-.0001);}return 1.;
}
shared uint gatherReady,gatherEligible;
#pragma cmi_include chunks/dome_completion.glsl
#pragma cmi_include chunks/dome_gather_audit.glsl
void main(){
 if(uMode==6u){uint slot=gl_WorkGroupID.x,lane=gl_LocalInvocationID.x;if(slot>=4u)return;if(lane==0u){gatherReady=0u;gatherEligible=0u;}barrier();uint ready=0u,eligible=0u;for(uint k=lane;k<384u;k+=64u){ready+=uint(bitCount(CMI_STATE(state,CMI_GATHER_READY+slot*384u+k)));eligible+=uint(bitCount(CMI_STATE(state,CMI_GATHER_ELIGIBLE+slot*384u+k)));}atomicAdd(gatherReady,ready);atomicAdd(gatherEligible,eligible);barrier();if(lane==0u){CMI_STATE(state,CMI_GATHER_COUNTS+slot*4u)=gatherReady;CMI_STATE(state,CMI_GATHER_COUNTS+slot*4u+1u)=gatherEligible;}return;}

 if(uMode==4u){if(gl_GlobalInvocationID.x==0u){CMI_STATE(state,CMI_DOME_CONTACT_ARGS)=0u;CMI_STATE(state,CMI_DOME_CONTACT_ARGS+7u)=0u;CMI_STATE(state,CMI_DOME_CONTACT_ARGS+4u)=(min(counter.live,uint(pool.data.length())/4u)+63u)/64u;CMI_STATE(state,CMI_DOME_CONTACT_ARGS+5u)=1u;CMI_STATE(state,CMI_DOME_CONTACT_ARGS+6u)=1u;}return;}
 if(uMode==1u){if(gl_GlobalInvocationID.x==0u){CMI_STATE(state,CMI_DOME_CONTACT_ARGS+1u)=(min(CMI_STATE(state,CMI_DOME_CONTACT_ARGS),131072u)+63u)/64u;CMI_STATE(state,CMI_DOME_CONTACT_ARGS+2u)=1u;CMI_STATE(state,CMI_DOME_CONTACT_ARGS+3u)=1u;}return;}
#if CMI_DOME_PASS == 4
 return;
#else
 uint rank=gl_GlobalInvocationID.x;
 uint i=rank;if(i>=min(counter.live,uint(pool.data.length())/4u))return;
 uint at=i*4u;vec4 p=pool.data[at],life=pool.data[at+3u];uint emitter=floatBitsToUint(life.w);
 if(emitter*VEC4_PER_EMITTER+18u>=uint(emitters.u.length())||emitters.u[emitter*VEC4_PER_EMITTER+18u].x<.5)return;
 float material=emitters.u[emitter*VEC4_PER_EMITTER+7u].x;if(material<1.5||material>=2.5)return;
 int owner=cmiDomeOwner(p.w);uint member=cmiStarMember(p.w);
 if(owner<0||life.y<=0.)return;uint phase=CMI_STATE(state,member*2u+1u)&255u;if(phase!=1u&&phase!=4u&&phase!=6u)return;
 uint assignment=cmiStarAssignment(p.w);
 float speedBudget=phase==1u?CMI_STORM_INTERCEPT:assignment==0u?CMI_STORM_CRUISE:CMI_DOME_MOTION_LIMIT(state,assignment,phase);
 float neighborBudget=max(CMI_STORM_INTERCEPT,uintBitsToFloat(cmiStarWord(3u)));
 vec3 old;if(!cmiDomeOld(member,old))return;uint record=CMI_DOME_CONTACT_DELTA+member*4u;
 if(uMode==5u){
  if(phase==1u){
   vec3 target,motion;cmiDomeSampleOffset(assignment,0.,target,motion);int terrain=uStormCollisionEnabled?cmiDomeBodyTerrain(target):0;
   if(cmiDomeGatherAudit(assignment,member,p.xyz,target,terrain,pool.data[at+1u].w))return;
   uint flow=uint(owner)*66u+cmiDomeFlow(assignment),flowAt=CMI_DOME_FLOW_STATE+flow*2u,window=CMI_DOME_GATHER_WINDOW+(uint(owner)*12288u+cmiMajorRank(assignment))*8u;
   uint word=uint(owner)*384u+cmiMajorRank(assignment)/32u,bit=1u<<(cmiMajorRank(assignment)&31u);
   float published=uintBitsToFloat(cmiStarWord(CMI_DOME_FLOW_SOURCE+flow*2u+1u));bool changed=isinf(published)&&uintBitsToFloat(CMI_STATE(state,flowAt+1u))<cmiDomePause(assignment);
   // The baked lane spacing assumes that near-reference following has taken
   // ownership. A merely close interceptor still carries a C2 join offset.
   // A contact correction can preserve acquisition when its final body still
   // lies in the near-reference band; navigation mode alone cannot reject it.
   bool following=(CMI_STATE(state,member*2u+1u)&0x40000000u)!=0u||distance(p.xyz,target)<.04;
   bool valid=following&&cmiStarWord(2u)!=0u&&!changed&&(CMI_STATE(state,window+7u)&6u)==0u&&terrain==0&&distance(p.xyz,target)<=.4&&distance(pool.data[at+1u].xyz,motion)<=1.;
   bool same=CMI_STATE(starStateRead,member*2u)==CMI_STATE(state,member*2u)&&(CMI_STATE(starStateRead,member*2u+1u)&255u)==1u&&CMI_STATE(starStateRead,window+5u)==floatBitsToUint(cmiDomePause(assignment));
   uint streak=valid?(same?min(3u,CMI_STATE(starStateRead,CMI_GATHER_STREAK+member)+1u):1u):0u;CMI_STATE(state,CMI_GATHER_STREAK+member)=streak;
   if(streak==3u)atomicOr(CMI_STATE(state,CMI_GATHER_READY+word),bit);else atomicAnd(CMI_STATE(state,CMI_GATHER_READY+word),~bit);
   if(valid&&distance(p.xyz,target)<.04)CMI_STATE(state,member*2u+1u)|=0x40000000u;else CMI_STATE(state,member*2u+1u)&=~0x40000000u;
  }
  if(phase==4u){uint a=cmiStarAssignment(p.w),b=cmiStarBase(uint(owner)),mapped=cmiDomeMappedRank(a);vec4 record=cmiDomeRecord(a),ring=cmiStarVec(CMI_DOME_DATA+8u+mapped*16u),end=cmiStarVec(CMI_DOME_DATA+16u+mapped*16u);
   if(CMI_STATE(state,member*2u)!=uint(cmiStarVec(b).x+.5))return;
   vec3 delta=p.xyz+vec3(0,.3,0)-cmiStarVec(b+24u).xyz;float heading=cmiStarVec(b+20u).z,c=cos(heading),s=sin(heading);vec3 local=vec3(c*delta.x+s*delta.z,delta.y,-s*delta.x+c*delta.z);
   float radius2=dot(delta,delta);bool inside=radius2<=1024.;
   uint previousFlags=CMI_STATE(state,member*2u+1u),flags=previousFlags;
   if(inside)flags|=CMI_DOME_INSIDE_BOUND;
   // Normal members remain on the cheap inside path. A fresh outer-shell
   // ingress has neither spatial history nor an exhausted reference route.
   bool escaped=false;
   if(!inside)escaped=(flags&CMI_DOME_INSIDE_BOUND)!=0u||cmiDomeElapsed(b,uTimeSec)-(cmiStarVec(b+4u).z-cmiStarVec(b+4u).x)>=record.z;
   uint base=uint(record.w),count=uint(ring.w);bool core=(base/12u+(mapped-base)%(count/12u))%16u==0u;
   bool entered=(flags&0x20000000u)!=0u||length(local)<(core?2.:8.5);
   if(entered)flags|=0x20000000u;
   if(flags!=previousFlags)CMI_STATE(state,member*2u+1u)=flags;
   bool finished=entered&&dot(local,end.yzw)>=ring.z;
   float blocked=uintBitsToFloat(CMI_STATE(state,CMI_GATHER_BLOCKED+member));
   if(distance(p.xyz,old)<.2*uDt){
    bool sameAttack=CMI_STATE(starStateRead,member*2u)==CMI_STATE(state,member*2u)&&(CMI_STATE(starStateRead,member*2u+1u)&255u)==4u;
    float previousBlocked=sameAttack?uintBitsToFloat(CMI_STATE(starStateRead,CMI_GATHER_BLOCKED+member)):0.;
    blocked=max(blocked,previousBlocked+uDt);
   }
   CMI_STATE(state,CMI_GATHER_BLOCKED+member)=floatBitsToUint(blocked);
   if(finished||escaped||blocked>=.5){
    uint rank=cmiMajorRank(a),word=uint(owner)*384u+rank/32u;
    CMI_DOME_COMPLETE(state,member,word,1u<<(rank&31u),finished,pool.data[at+1u].w);
   }
  }return;
 }

 if(uMode==3u){
  if(CMI_STATE(state,CMI_DOME_CONTACT_ARGS)==0u&&CMI_STATE(state,CMI_DOME_CONTACT_ARGS+7u)==0u)return;
  vec3 motion=vec3(uintBitsToFloat(CMI_STATE(state,record)),uintBitsToFloat(CMI_STATE(state,record+1u)),uintBitsToFloat(CMI_STATE(state,record+2u)));
  float fraction=1.,reach=CMI_BODY_RADIUS+(2.*speedBudget+neighborBudget)*uDt;ivec3 origin=ivec3(floor(old/CMI_BODY_CELL));int cells=int(ceil(reach/CMI_BODY_CELL)),visits=0;
  uint stoppedCount=CMI_STATE(state,CMI_DOME_CONTACT_ARGS+7u);
  if(phase==4u&&stoppedCount<=8u){for(uint k=0u;k<stoppedCount;k++){
   uint otherMember=CMI_STATE(state,CMI_DOME_CONTACT_QUEUE+k);if(otherMember==member||CMI_STATE(state,otherMember*2u)==CMI_STATE(state,member*2u))continue;
   vec3 otherOld;if(!cmiDomeOld(otherMember,otherOld)||distance(old,otherOld)>reach)continue;uint otherRecord=CMI_DOME_CONTACT_DELTA+otherMember*4u;
   vec3 otherMotion=vec3(uintBitsToFloat(CMI_STATE(state,otherRecord)),uintBitsToFloat(CMI_STATE(state,otherRecord+1u)),uintBitsToFloat(CMI_STATE(state,otherRecord+2u)));fraction=min(fraction,cmiDomeSweepFraction(old-otherOld,motion-otherMotion));
  }}
  // All proposals are immutable here. Reading the neighbour's already-mutated
  // pool position would make clipping depend on work-group execution order.
  for(int x=-cells;x<=cells&&phase==4u&&stoppedCount>8u;x++)for(int y=-cells;y<=cells;y++)for(int z=-cells;z<=cells;z++){
   ivec3 cell=origin+ivec3(x,y,z);vec3 closest=clamp(old,vec3(cell)*CMI_BODY_CELL,(vec3(cell)+1.)*CMI_BODY_CELL);if(distance(old,closest)>reach)continue;
   uint hash=(uint(cell.x)*92837111u^uint(cell.y)*689287499u^uint(cell.z)*283923481u)&(GRID_TABLE-1u);int node=grid.heads[hash];
   while(node!=0){uint j=uint(node-1);node=grid.next[j];if(++visits>768)break;if(j==i)continue;vec4 other=pool.data[j*4u],otherLife=pool.data[j*4u+3u];if(otherLife.y<=0.||floatBitsToUint(otherLife.w)!=emitter)continue;
    uint otherMember=cmiStarMember(other.w);vec3 otherOld;if(!cmiDomeOld(otherMember,otherOld))continue;vec3 otherMotion=other.xyz-otherOld;
    uint otherPhase=otherMember<131072u?CMI_STATE(state,otherMember*2u+1u)&255u:0u;bool same=cmiDomeOwner(other.w)==owner&&CMI_STATE(state,otherMember*2u)==CMI_STATE(state,member*2u);
    if(same)continue;if(cmiDomeOwner(other.w)>=0&&(otherPhase==1u||otherPhase==4u||otherPhase==6u)){uint otherRecord=CMI_DOME_CONTACT_DELTA+otherMember*4u;if((CMI_STATE(state,otherRecord+3u)&0x40000000u)==0u)continue;otherMotion=vec3(uintBitsToFloat(CMI_STATE(state,otherRecord)),uintBitsToFloat(CMI_STATE(state,otherRecord+1u)),uintBitsToFloat(CMI_STATE(state,otherRecord+2u)));}
    fraction=min(fraction,cmiDomeSweepFraction(old-otherOld,motion-otherMotion));
   }
  }
  // Reconstructing old + (candidate - old) can differ by one float ULP.
  // An unchanged proposal must never invalidate following/readiness merely
  // because a full-pool application pass happens to visit it.
  if(fraction==1.&&(CMI_STATE(state,record+3u)&0x80000000u)==0u)return;
  vec3 destination=old+motion*fraction,correction=destination-p.xyz;if(length(correction)<.00001)return;
  float maximum=speedBudget*uDt;
  if(uStormCollisionEnabled){
   CmiStormBody body=cmiStormBody(destination);if(!body.complete)return;
   if(body.occupied){
    destination=p.xyz;for(int k=0;k<3;k++){vec3 next=destination;next[k]+=correction[k];if(distance(next,old)<=maximum+.00001&&cmiDomeBodyTerrain(next)==0)destination=next;}
   }
  }
  if(distance(destination,p.xyz)<.000001)return;
  pool.data[at].xyz=destination;vec3 actualVelocity=(destination-old)/max(uDt,.0001);float actualSpeed=length(actualVelocity);pool.data[at+1u].xyz=actualSpeed>speedBudget?actualVelocity*(speedBudget/actualSpeed):actualVelocity;CMI_STATE(state,member*2u+1u)|=0x80000000u;CMI_STATE(state,member*2u+1u)&=~0x40000000u;
  pool.data[at+1u].w=phase==4u&&cmiNearPlayerFeet(destination)?-4.:0.;
  return;
 }
 vec3 preferred=(p.xyz-old)/max(uDt,.0001),gatherCorrection=vec3(0);float preferredSpeed=length(preferred);if(preferredSpeed>speedBudget)preferred*=speedBudget/preferredSpeed;float gatherWorst=0.;vec4 blockingPlane=vec4(0);float blockingPenalty=0.;bool hazard=false;
 float horizon=uDt,relativeBudget=speedBudget+neighborBudget,reach=CMI_BODY_RADIUS+relativeBudget*horizon+speedBudget*uDt;ivec3 center=ivec3(floor(old/CMI_BODY_CELL));int cells=int(ceil(reach/CMI_BODY_CELL)),visits=0;bool exhausted=false;
 for(int x=0;x<=cells*2&&!exhausted;x++)for(int y=0;y<=cells*2&&!exhausted;y++)for(int z=0;z<=cells*2&&!exhausted;z++){
  ivec3 offset=ivec3(x==0?0:(x+1)/2*(x%2==1?1:-1),y==0?0:(y+1)/2*(y%2==1?1:-1),z==0?0:(z+1)/2*(z%2==1?1:-1));
  ivec3 cell=center+offset;vec3 closest=clamp(old,vec3(cell)*CMI_BODY_CELL,(vec3(cell)+1.)*CMI_BODY_CELL);if(distance(old,closest)>reach)continue;
  uint hash=(uint(cell.x)*92837111u^uint(cell.y)*689287499u^uint(cell.z)*283923481u)&(GRID_TABLE-1u);int node=grid.heads[hash];
  while(node!=0){uint j=uint(node-1);node=grid.next[j];if(++visits>768){exhausted=true;break;}if(j==i)continue;
   vec4 other=pool.data[j*4u];if(distance(p.xyz,other.xyz)>CMI_BODY_RADIUS+relativeBudget*horizon)continue;
   vec4 otherLife=pool.data[j*4u+3u];if(otherLife.y<=0.||floatBitsToUint(otherLife.w)!=floatBitsToUint(life.w))continue;
   uint otherMember=cmiStarMember(other.w),otherPhase=otherMember<131072u?CMI_STATE(state,otherMember*2u+1u)&255u:0u;
   bool same=cmiDomeOwner(other.w)==owner&&CMI_STATE(state,otherMember*2u)==CMI_STATE(state,member*2u)&&(phase!=1u||otherPhase==1u);
   bool priority=(CMI_STATE(state,member*2u+1u)&0x40000000u)!=0u,otherPriority=otherMember<131072u&&(CMI_STATE(state,otherMember*2u+1u)&0x40000000u)!=0u;
   // Baked/gathered traffic has right of way. A returning or approaching
   // member yields once, rather than both sides braking a certified lane.
   bool stopped=otherMember<131072u&&(CMI_STATE(starStateRead,otherMember*2u+1u)&0x80000000u)!=0u&&length(pool.data[j*4u+1u].xyz)<.5;
   if(same&&(priority||phase==4u))continue;
   vec3 otherOld;if(!cmiDomeOld(otherMember,otherOld))otherOld=other.xyz-pool.data[j*4u+1u].xyz*uDt;
   vec3 relative=old-otherOld;float initial=length(relative);if(initial>CMI_BODY_RADIUS+relativeBudget*horizon)continue;
   vec3 normal;
   if(initial>.000001)normal=relative/initial;else{float angle=float(min(member,otherMember))*2.39996323;normal=normalize(vec3(cos(angle),.3,sin(angle)))*(member<otherMember?1.:-1.);}
   vec3 otherVelocity=(other.xyz-otherOld)/max(uDt,.0001);
   float otherSpeed=length(otherVelocity);if(otherSpeed>neighborBudget)otherVelocity*=neighborBudget/otherSpeed;
   if(stopped)otherVelocity=vec3(0);
   if(phase==1u){
    vec3 motion=(preferred-otherVelocity)*uDt;float toi=1.;
    if(initial>=CMI_BODY_RADIUS){float aa=dot(motion,motion),bb=dot(relative,motion),cc=dot(relative,relative)-CMI_BODY_RADIUS*CMI_BODY_RADIUS,discriminant=bb*bb-aa*cc;if(aa<.00000001||bb>=0.||discriminant<0.)continue;toi=(-bb-sqrt(discriminant))/aa;if(toi<0.||toi>=1.)continue;normal=normalize(relative+motion*toi);}
    float amount=initial<CMI_BODY_RADIUS?max(0.,(CMI_BODY_RADIUS-initial)*min(uDt/.5,1.)-dot(motion,normal)):max(0.,-dot(motion,normal))*(1.-toi);
    if(amount>gatherWorst){gatherWorst=amount;gatherCorrection=normal*amount;hazard=true;}continue;
   }
   // Only an actual predicted body encounter constrains velocity. Nearby
   // parallel lanes are not obstacles: imposing their tangent half-spaces
   // creates infeasible clusters in an otherwise separated baked stream.
   vec3 relativeVelocity=preferred-otherVelocity;float rv2=dot(relativeVelocity,relativeVelocity),closestTime=rv2>.000001?clamp(-dot(relative,relativeVelocity)/rv2,0.,horizon):0.;
   // Only the most dangerous actual swept contact receives a velocity clip.
   bool contact=length(relative+relativeVelocity*closestTime)<CMI_BODY_RADIUS;
   if(!contact)continue;hazard=true;
   bool reciprocal=cmiDomeOwner(other.w)>=0&&!otherPriority&&!stopped&&(otherPhase==1u||otherPhase==4u||otherPhase==6u);
   float bound=reciprocal?dot(normal,(preferred+otherVelocity)*.5)-max(0.,initial-CMI_BODY_RADIUS)/(2.*horizon):dot(normal,otherVelocity)-max(0.,initial-CMI_BODY_RADIUS)/horizon;
   if(initial<CMI_BODY_RADIUS)bound+=min(.4,(CMI_BODY_RADIUS-initial)/.5)*(reciprocal?.5:1.);
   float penalty=bound-dot(normal,preferred);if(penalty>blockingPenalty){blockingPenalty=penalty;blockingPlane=vec4(normal,bound);}

  }
 }

 bool feasible=true;vec3 resolved=blockingPenalty>0.?cmiDomeContactVelocity(preferred,blockingPlane,speedBudget,feasible):preferred;
 // An infeasible body cluster may move conservatively while its existing
 // half-second stall watchdog decides whether a harmless exit is necessary.
 if(!feasible)resolved=vec3(0);
 vec3 correction=(phase==1u?gatherCorrection:(resolved-preferred)*uDt);

 vec3 proposed=p.xyz-old+correction;float travel=length(proposed);if(travel>speedBudget*uDt)proposed*=speedBudget*uDt/travel;
 for(uint k=0u;k<3u;k++)CMI_STATE(state,record+k)=floatBitsToUint(proposed[k]);
 if(uMode==0u){if(!feasible){uint queue=atomicAdd(CMI_STATE(state,CMI_DOME_CONTACT_ARGS+7u),1u);if(queue<131072u)CMI_STATE(state,CMI_DOME_CONTACT_QUEUE+queue)=member;}CMI_STATE(state,record+3u)=uint(visits)|(blockingPenalty>0.?0x10000u:0u)|(!feasible?0x40000000u:0u)|(hazard?0x80000000u:0u);if(hazard){atomicAdd(CMI_STATE(state,CMI_DOME_CONTACT_ARGS),1u);}}
#endif
}
