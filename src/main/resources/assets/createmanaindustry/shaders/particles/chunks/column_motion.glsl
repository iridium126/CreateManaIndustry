bool cmiColumnExit=false,cmiColumnRestored=false,cmiColumnRecoveryFrame=false;vec3 cmiColumnExitVelocity=vec3(0);
// Identity-keyed member state; immutable curve lengths are initialized once on departure.
vec3 cmiColumnState3(uint s){return vec3(uintBitsToFloat(CMI_STATE(starStateWrite,s)),uintBitsToFloat(CMI_STATE(starStateWrite,s+1u)),uintBitsToFloat(CMI_STATE(starStateWrite,s+2u)));}
void cmiColumnStore3(uint s,vec3 v){CMI_STATE(starStateWrite,s)=floatBitsToUint(v.x);CMI_STATE(starStateWrite,s+1u)=floatBitsToUint(v.y);CMI_STATE(starStateWrite,s+2u)=floatBitsToUint(v.z);}
float cmiColumnState(uint s){return uintBitsToFloat(CMI_STATE(starStateWrite,s));}
void cmiColumnStore(uint s,float v){CMI_STATE(starStateWrite,s)=floatBitsToUint(v);}
vec3 cmiColumnApproach(uint s,uint b,uint rank,float time){
 vec4 t=cmiStarVec(b+4u);float duration=max(t.y-t.x,.1),u=clamp((time-t.x)/duration,0.,1.);
 vec3 origin=cmiColumnState3(s+4u),v=cmiColumnState3(s+8u);
 return mix(origin,cmiColumnGatherAt(b,rank,time),cmiColumnSmooth(u))+v*(duration*u*pow(1.-u,3.));
}
bool cmiColumnFloor(vec3 p){
 if(cmiStormVoxel)return cmiStormKnown(p)&&!cmiStormSolid(p+vec3(0,.1,0))&&cmiStormSolid(p-vec3(0,.2,0))&&!cmiStormMissing;
 if(uCollisionOn!=1)return false;
 for(int i=0;i<BAKE_SLICES;i++){vec4 m=bakeMeta.m[i];vec3 rel=p-m.xyz;
  if(m.w>.5&&all(greaterThanEqual(rel,vec3(.2)))&&all(lessThan(rel,vec3(SX,SY,SZ)-vec3(.2))))return !solidAt(p+vec3(0,.1,0),m.xyz,i)&&solidAt(p-vec3(0,.2,0),m.xyz,i);
 }return false;
}
struct CmiColumnCurve {float radius,root,angle,endAngle,height,endHeight,radial,angular,vertical,arcWarp;};
CmiColumnCurve cmiColumnCurveParameters(uint arc,uint b,uint rank){
 vec3 start=cmiColumnState3(arc+CMI_COLUMN_ORIGIN)-cmiStarLanding(b,0u).xyz,direction=cmiColumnState3(arc+CMI_COLUMN_DIRECTION);vec4 t=cmiStarVec(b+4u);
 float radius=max(length(start.xz),.2),root=cmiColumnRoot(rank),angle=atan(start.z,start.x),endAngle=angle+.7*max(t.z+cmiColumnDelay(rank)-t.y,.1);
 endAngle+=cmiColumnWrap(cmiColumnAngle(b,rank)-endAngle);
 vec2 radial=vec2(cos(angle),sin(angle));
 return CmiColumnCurve(radius,root,angle,endAngle,start.y,cmiColumnHeight(root),clamp(dot(direction.xz,radial)*4.,-.25*radius,.25*radius),dot(direction.xz,vec2(-radial.y,radial.x))*4./radius,direction.y*4.,mix(.1,.2,smoothstep(1.,4.,max(0.,start.y)/radius)));
}
// Monotone integrated P4 grid concentrates knots where the speed changes.
// Geometry is unchanged; all 128 chord samples and the inverse share this grid.
float cmiColumnArcGrid(float t,float strength){return t+strength*t*(1.-t)*(1.-2.*t)*(1.-7.*t+7.*t*t);}
vec3 cmiColumnCurvePoint(CmiColumnCurve p,float u){
 float h0=u*(1.-u)*(1.-u),h1=u*u*(u-1.),blend=cmiColumnSmooth(u);
 float radius=max(.2,mix(p.radius,p.root,blend)+p.radial*h0+.35*h1),angle=mix(p.angle,p.endAngle,blend)+p.angular*h0;
 return vec3(cos(angle)*radius,mix(p.height,p.endHeight,blend)+p.vertical*h0+.35*p.root*.125*h1,sin(angle)*radius);
}
vec3 cmiColumnCurve(uint s,uint arc,uint b,uint rank,float u){return cmiColumnCurvePoint(cmiColumnCurveParameters(arc,b,rank),u);}
void cmiColumnCurveInit(uint s,uint arc,uint b,uint rank,vec3 pos,vec3 vel,float begin){
 cmiColumnStore(s+2u,begin);cmiColumnStore3(arc+CMI_COLUMN_ORIGIN,pos);
 vec3 direction=length(vel)>.01?normalize(vel):normalize(cmiColumnOrbitVelocity(b,rank,begin));cmiColumnStore3(arc+CMI_COLUMN_DIRECTION,direction);
 CmiColumnCurve parameters=cmiColumnCurveParameters(arc,b,rank);
 float lengthTotal=0.;vec3 previous=pos-cmiStarLanding(b,0u).xyz;cmiColumnStore(arc,0.);
 for(uint i=1u;i<=CMI_COLUMN_ARC_SAMPLES;i++){vec3 point=cmiColumnCurvePoint(parameters,cmiColumnArcGrid(float(i)/float(CMI_COLUMN_ARC_SAMPLES),parameters.arcWarp));lengthTotal+=distance(point,previous);if((i&1u)==0u)cmiColumnStore(cmiColumnArcNode(arc,i/2u),lengthTotal);previous=point;}
 cmiColumnStore(s+15u,0.);CMI_STATE(starStateWrite,arc+CMI_COLUMN_PHASE)=2u;cmiColumnStore3(arc+CMI_COLUMN_TANGENT,direction);
}
float cmiColumnParameter(uint arc,float distanceArc,float warp){
 if(distanceArc<=0.)return 0.;float total=cmiColumnState(cmiColumnArcNode(arc,CMI_COLUMN_ARC_NODES-1u));if(distanceArc>=total)return 1.;
 uint lo=0u,hi=CMI_COLUMN_ARC_NODES-1u;for(int i=0;i<6;i++){uint mid=(lo+hi)/2u;if(cmiColumnState(cmiColumnArcNode(arc,mid))<distanceArc)lo=mid;else hi=mid;}
 float start=cmiColumnState(cmiColumnArcNode(arc,lo)),delta=max(cmiColumnState(cmiColumnArcNode(arc,hi))-start,.000001);
 return mix(cmiColumnArcGrid(float(lo)/64.,warp),cmiColumnArcGrid(float(hi)/64.,warp),clamp((distanceArc-start)/delta,0.,1.));
}
vec3 cmiColumnTurn(vec3 from,vec3 to,float radians){
 from=normalize(from);to=normalize(to);float angle=acos(clamp(dot(from,to),-1.,1.));if(angle<.0001)return to;
 vec3 axis=cross(from,to);if(length(axis)<.0001)axis=cross(from,abs(from.y)<.9?vec3(0,1,0):vec3(1,0,0));axis=normalize(axis);
 float a=min(angle,radians);return normalize(from*cos(a)+cross(axis,from)*sin(a)+axis*dot(axis,from)*(1.-cos(a)));
}
void cmiColumnBloomInit(uint s,uint arc,uint b,vec3 point,float time){
 vec3 landing=cmiStarLanding(b,0u).xyz;vec2 delta=point.xz-landing.xz;float r=clamp(length(delta),0.,4.);
 cmiColumnStore(s+1u,time);cmiColumnStore3(s+12u,point);cmiColumnStore(s+15u,0.);
 cmiColumnStore(arc+CMI_COLUMN_BLOOM_RADIUS,r);cmiColumnStore(arc+CMI_COLUMN_BLOOM_ANGLE,atan(delta.y,delta.x));CMI_STATE(starStateWrite,arc+CMI_COLUMN_PHASE)=4u;
}
bool cmiColumnSteerAt(uint a,vec3 pos,vec3 vel,vec3 home,vec3 homeVelocity,float time,float delta,out vec3 target,out vec3 feed,out uint phase,out float marker){
 uint slot=cmiStarSlot(a),rank=cmiStarRank(a),b=cmiStarBase(slot),s=262592u+(slot-4u)*1024u+rank*16u,arc=328128u+((slot-4u)*64u+rank)*CMI_COLUMN_ARC_WORDS;
 vec4 t=cmiStarVec(b+4u);uint id=uint(cmiStarVec(b).x+.5);
 target=home;feed=vec3(0);phase=0u;marker=0.;if(time<t.x||time>t.w||cmiStarVec(b).y>2.5)return false;
 if(cmiStarVec(b+24u).z>.5){target=pos;feed=vec3(0);phase=1u;return true;}
 if(!cmiColumnRestored){
  for(uint i=0u;i<16u;i++)CMI_STATE(starStateWrite,s+i)=CMI_STATE(starStateRead,s+i);
  for(uint i=33u;i<48u;i++)CMI_STATE(starStateWrite,arc+i)=CMI_STATE(starStateRead,arc+i);
  if(CMI_STATE(starStateRead,arc+CMI_COLUMN_PHASE)==2u)for(uint i=0u;i<33u;i++){CMI_STATE(starStateWrite,arc+i)=CMI_STATE(starStateRead,arc+i);CMI_STATE(starStateWrite,arc+48u+i)=CMI_STATE(starStateRead,arc+48u+i);}
 }
 if(CMI_STATE(starStateWrite,s)!=id){
  for(uint i=0u;i<16u;i++)CMI_STATE(starStateWrite,s+i)=0u;for(uint i=0u;i<CMI_COLUMN_ARC_WORDS;i++)CMI_STATE(starStateWrite,arc+i)=0u;
  CMI_STATE(starStateWrite,s)=id;cmiColumnStore(s+1u,-1.);cmiColumnStore(s+2u,-1.);cmiColumnStore(s+3u,-1.);cmiColumnStore3(s+4u,pos);cmiColumnStore3(s+8u,vel);
 }
 uint state=CMI_STATE(starStateWrite,arc+CMI_COLUMN_PHASE);float step=max(delta,.000001),remaining=6.*step;vec3 point=pos,motion=vec3(0);
 if(state==6u){phase=6u;marker=6.;return false;}
 if(state==0u){
  if(time<t.y){phase=1u;marker=3.;target=cmiColumnApproach(s,b,rank,time);feed=(target-pos)/step;return true;}
  vec3 direction=vel;float begin=time-step;
  if(distance(pos,cmiColumnGatherAt(b,rank,begin))<=.4&&distance(vel,cmiColumnOrbitVelocity(b,rank,begin))<=1.){
   vec2 radial=pos.xz-cmiStarLanding(b,0u).xz;
   if(length(radial)>.0001){radial=normalize(radial);direction=vec3(-radial.y*CMI_COLUMN_ORBIT_SPEED,-sqrt(36.-CMI_COLUMN_ORBIT_SPEED*CMI_COLUMN_ORBIT_SPEED),radial.x*CMI_COLUMN_ORBIT_SPEED);}
  }
  cmiColumnCurveInit(s,arc,b,rank,pos,direction,begin);state=2u;
 }
 // A collision can end descent before the reference endpoint on a hill or a stair.
 if(state==2u&&cmiColumnFloor(pos)&&fract(pos.y)<.04){cmiColumnBloomInit(s,arc,b,pos,time-step);state=4u;}
 float travelled=cmiColumnState(s+15u);
 if(state==2u){
   float total=cmiColumnState(cmiColumnArcNode(arc,CMI_COLUMN_ARC_NODES-1u)),move=min(remaining,max(0.,total-travelled));
   CmiColumnCurve curve=cmiColumnCurveParameters(arc,b,rank);
   vec3 before=cmiColumnCurvePoint(curve,cmiColumnParameter(arc,travelled,curve.arcWarp)),after=cmiColumnCurvePoint(curve,cmiColumnParameter(arc,travelled+move,curve.arcWarp));
   vec3 delta=after-before;motion+=length(delta)>.000001?normalize(delta)*move:vec3(0);point=pos+motion;remaining-=move;travelled+=move;cmiColumnStore(s+15u,travelled);
   if(length(after-before)>.00001)cmiColumnStore3(arc+CMI_COLUMN_TANGENT,normalize(after-before));
   if(travelled<total-.00001){marker=4.+clamp(travelled/max(total,.00001),0.,.99999);phase=2u;target=point;feed=motion/step;return true;}
   // Reference progress never waits for collision displacement to be recovered.
   if(cmiColumnFloor(point)&&length(point.xz-cmiStarLanding(b,0u).xz)<=4.){cmiColumnBloomInit(s,arc,b,point,time-remaining/6.);state=4u;}
   else{state=5u;CMI_STATE(starStateWrite,arc+CMI_COLUMN_PHASE)=5u;cmiColumnStore(s+3u,time-remaining/6.);}
 }
 if(state==4u){
   travelled=cmiColumnState(s+15u);
   float r0=cmiColumnState(arc+CMI_COLUMN_BLOOM_RADIUS),angle=cmiColumnState(arc+CMI_COLUMN_BLOOM_ANGLE),start=cmiColumnArc(r0),total=cmiColumnArc(4.)-start;
   float move=min(remaining,max(0.,total-travelled)),r=cmiColumnRadius(start+travelled),next=cmiColumnRadius(start+travelled+move);
   motion+=vec3(cos(angle)*(next-r),cmiColumnHeight(next)-cmiColumnHeight(r),sin(angle)*(next-r));point=pos+motion;remaining-=move;travelled+=move;cmiColumnStore(s+15u,travelled);
   cmiColumnStore3(arc+CMI_COLUMN_TANGENT,cmiColumnTangent(angle,next));
   if(travelled<total-.00001){marker=-1.-clamp(travelled/max(total,.00001),0.,1.);phase=4u;target=point;feed=motion/step;return true;}
   state=5u;CMI_STATE(starStateWrite,arc+CMI_COLUMN_PHASE)=5u;cmiColumnStore(s+3u,time-remaining/6.);marker=0.;
 }
 if(state==5u){
  marker=5.;float began=cmiColumnState(s+3u),age=max(0.,time-began);
  float available=max(0.,.5-max(0.,time-step-began)),move=min(remaining,available*6.);
  vec3 delta=home-point,direction=cmiColumnState3(arc+CMI_COLUMN_TANGENT);
  if(length(delta)>.00001)direction=cmiColumnTurn(direction,delta,2.5*min(step,.5)*cmiColumnSmooth(clamp(age/.5,0.,1.)));
  cmiColumnStore3(arc+CMI_COLUMN_TANGENT,direction);motion+=direction*move;point=pos+motion;remaining-=move;
  if(age>=.5){
   float ordinary=remaining/6.;vec3 finalVelocity=direction*6.;
   if(ordinary>0.){finalVelocity+=((home-point)*24.+(homeVelocity-finalVelocity)*6.)*ordinary;float speed=length(finalVelocity);if(speed>6.)finalVelocity*=6./speed;motion+=finalVelocity*ordinary;point=pos+motion;}
   cmiColumnExit=true;cmiColumnExitVelocity=finalVelocity;CMI_STATE(starStateWrite,arc+CMI_COLUMN_PHASE)=6u;phase=6u;marker=6.;target=point;feed=motion/step;return true;
  }

 }
 phase=state;target=point;feed=motion/step;return true;
}
bool cmiColumnSteer(uint a,vec3 pos,vec3 vel,vec3 home,vec3 homeVelocity,out vec3 target,out vec3 feed,out uint phase,out float marker){return cmiColumnSteerAt(a,pos,vel,home,homeVelocity,cmiStarTime(cmiStarBase(cmiStarSlot(a)),uTimeSec),uDt,target,feed,phase,marker);}

bool cmiColumnConsumeRecovery(uint assignment,inout vec3 pos,inout vec3 velocity){
 uint arc=328128u+((cmiStarSlot(assignment)-4u)*64u+cmiStarRank(assignment))*CMI_COLUMN_ARC_WORDS;
 if(uStormFrame==0u||CMI_STATE(starStateWrite,arc+CMI_COLUMN_RECOVER_GENERATION)!=uStormFrame)return false;
 pos=cmiColumnState3(arc+CMI_COLUMN_RECOVER_POS);velocity=cmiColumnState3(arc+CMI_COLUMN_RECOVER_VEL);
 CMI_STATE(starStateWrite,arc+CMI_COLUMN_RECOVER_GENERATION)=uStormFrame|0x80000000u;
 cmiColumnRecoveryFrame=true;cmiColumnRestored=true;cmiColumnExit=true;cmiColumnExitVelocity=velocity;return true;
}
