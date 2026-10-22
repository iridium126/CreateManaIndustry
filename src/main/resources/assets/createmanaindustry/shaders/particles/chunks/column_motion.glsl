bool cmiColumnExit=false,cmiColumnRestored=false,cmiColumnRecoveryFrame=false;vec3 cmiColumnExitVelocity=vec3(0);
// Identity-keyed member state; immutable curve lengths are initialized once on departure.
vec3 cmiColumnState3(uint s){return vec3(uintBitsToFloat(starStateWrite.words[s]),uintBitsToFloat(starStateWrite.words[s+1u]),uintBitsToFloat(starStateWrite.words[s+2u]));}
void cmiColumnStore3(uint s,vec3 v){starStateWrite.words[s]=floatBitsToUint(v.x);starStateWrite.words[s+1u]=floatBitsToUint(v.y);starStateWrite.words[s+2u]=floatBitsToUint(v.z);}
float cmiColumnState(uint s){return uintBitsToFloat(starStateWrite.words[s]);}
void cmiColumnStore(uint s,float v){starStateWrite.words[s]=floatBitsToUint(v);}
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
vec3 cmiColumnCurve(uint s,uint arc,uint b,uint rank,float u){
 float begin=cmiColumnState(s+2u);vec4 t=cmiStarVec(b+4u);float duration=max(t.z+cmiColumnDelay(rank)-t.y,.1);
 vec3 landing=cmiStarLanding(b,0u).xyz,start=cmiColumnState3(arc+33u)-landing,direction=cmiColumnState3(arc+36u);
 float r0=max(length(start.xz),.2),r1=cmiColumnRoot(rank),a0=atan(start.z,start.x);
 float a1=a0+.7*duration;a1+=cmiColumnWrap(cmiColumnAngle(b,rank)-a1);
 float h0=u*(1.-u)*(1.-u),h1=u*u*(u-1.),blend=cmiColumnSmooth(u);
 vec2 radial=vec2(cos(a0),sin(a0));float dr=clamp(dot(direction.xz,radial)*4.,-.25*r0,.25*r0);
 float radius=max(.2,mix(r0,r1,blend)+dr*h0+.35*h1);
 float angular=dot(direction.xz,vec2(-radial.y,radial.x))*4./r0;
 float angle=mix(a0,a1,blend)+angular*h0;
 float height=mix(start.y,cmiColumnHeight(r1),blend)+direction.y*4.*h0+.35*r1*.125*h1;
 return vec3(cos(angle)*radius,height,sin(angle)*radius);
}
void cmiColumnCurveInit(uint s,uint arc,uint b,uint rank,vec3 pos,vec3 vel,float begin){
 cmiColumnStore(s+2u,begin);cmiColumnStore3(arc+33u,pos);
 vec3 direction=length(vel)>.01?normalize(vel):normalize(cmiColumnOrbitVelocity(b,rank,begin));cmiColumnStore3(arc+36u,direction);
 float lengthTotal=0.;vec3 previous=pos-cmiStarLanding(b,0u).xyz;cmiColumnStore(arc,0.);
 for(uint i=1u;i<=128u;i++){vec3 point=cmiColumnCurve(s,arc,b,rank,float(i)/128.);lengthTotal+=distance(point,previous);if((i&3u)==0u)cmiColumnStore(arc+i/4u,lengthTotal);previous=point;}
 // Cache metric derivatives once. Cubic arc inversion avoids slow/fast bands between LUT knots.
 for(uint i=0u;i<=32u;i++){float u=float(i)/32.,lo=max(0.,u-1./1024.),hi=min(1.,u+1./1024.);float metric=distance(cmiColumnCurve(s,arc,b,rank,lo),cmiColumnCurve(s,arc,b,rank,hi))/(hi-lo);cmiColumnStore(arc+48u+i,metric);}
 cmiColumnStore(s+15u,0.);starStateWrite.words[arc+43u]=2u;cmiColumnStore3(arc+40u,direction);
}
float cmiColumnParameter(uint arc,float distanceArc){
 if(distanceArc<=0.)return 0.;float total=cmiColumnState(arc+32u);if(distanceArc>=total)return 1.;
 uint lo=0u,hi=32u;for(int i=0;i<5;i++){uint mid=(lo+hi)/2u;if(cmiColumnState(arc+mid)<distanceArc)lo=mid;else hi=mid;}
 float start=cmiColumnState(arc+lo),delta=max(cmiColumnState(arc+hi)-start,.000001),goal=distanceArc-start,q=clamp(goal/delta,0.,1.);
 float m0=clamp(cmiColumnState(arc+48u+lo)/32.,.000001,3.*delta),m1=clamp(cmiColumnState(arc+48u+hi)/32.,.000001,3.*delta);
 float a=m0+m1-2.*delta,b=3.*delta-2.*m0-m1;
 for(int i=0;i<3;i++){float value=((a*q+b)*q+m0)*q,derivative=(3.*a*q+2.*b)*q+m0;q=clamp(q-(value-goal)/max(derivative,.000001),0.,1.);}
 return (float(lo)+q)/32.;
}
vec3 cmiColumnTurn(vec3 from,vec3 to,float radians){
 from=normalize(from);to=normalize(to);float angle=acos(clamp(dot(from,to),-1.,1.));if(angle<.0001)return to;
 vec3 axis=cross(from,to);if(length(axis)<.0001)axis=cross(from,abs(from.y)<.9?vec3(0,1,0):vec3(1,0,0));axis=normalize(axis);
 float a=min(angle,radians);return normalize(from*cos(a)+cross(axis,from)*sin(a)+axis*dot(axis,from)*(1.-cos(a)));
}
void cmiColumnBloomInit(uint s,uint arc,uint b,vec3 point,float time){
 vec3 landing=cmiStarLanding(b,0u).xyz;vec2 delta=point.xz-landing.xz;float r=clamp(length(delta),0.,4.);
 cmiColumnStore(s+1u,time);cmiColumnStore3(s+12u,point);cmiColumnStore(s+15u,0.);
 cmiColumnStore(arc+45u,r);cmiColumnStore(arc+46u,atan(delta.y,delta.x));starStateWrite.words[arc+43u]=4u;
}
bool cmiColumnSteerAt(uint a,vec3 pos,vec3 vel,vec3 home,vec3 homeVelocity,float time,float delta,out vec3 target,out vec3 feed,out uint phase,out float marker){
 uint slot=cmiStarSlot(a),rank=cmiStarRank(a),b=cmiStarBase(slot),s=262592u+(slot-4u)*1024u+rank*16u,arc=328128u+((slot-4u)*64u+rank)*CMI_COLUMN_ARC_WORDS;
 vec4 t=cmiStarVec(b+4u);uint id=uint(cmiStarVec(b).x+.5);
 target=home;feed=vec3(0);phase=0u;marker=0.;if(time<t.x||time>t.w||cmiStarVec(b).y>2.5)return false;
 if(cmiStarVec(b+24u).z>.5){target=pos;feed=vec3(0);phase=1u;return true;}
 if(!cmiColumnRestored){
  for(uint i=0u;i<16u;i++)starStateWrite.words[s+i]=starStateRead.words[s+i];
  for(uint i=33u;i<48u;i++)starStateWrite.words[arc+i]=starStateRead.words[arc+i];
  if(starStateRead.words[arc+43u]==2u)for(uint i=0u;i<33u;i++){starStateWrite.words[arc+i]=starStateRead.words[arc+i];starStateWrite.words[arc+48u+i]=starStateRead.words[arc+48u+i];}
 }
 if(starStateWrite.words[s]!=id){
  for(uint i=0u;i<16u;i++)starStateWrite.words[s+i]=0u;for(uint i=0u;i<CMI_COLUMN_ARC_WORDS;i++)starStateWrite.words[arc+i]=0u;
  starStateWrite.words[s]=id;cmiColumnStore(s+1u,-1.);cmiColumnStore(s+2u,-1.);cmiColumnStore(s+3u,-1.);cmiColumnStore3(s+4u,pos);cmiColumnStore3(s+8u,vel);
 }
 uint state=starStateWrite.words[arc+43u];float step=max(delta,.000001),remaining=6.*step;vec3 point=pos,motion=vec3(0);
 if(state==6u){phase=6u;marker=6.;return false;}
 if(state==0u){
  if(time<t.y){phase=1u;marker=3.;target=cmiColumnApproach(s,b,rank,time);feed=(target-pos)/step;return true;}
  cmiColumnCurveInit(s,arc,b,rank,pos,vel,time-step);state=2u;
 }
 // A collision can end descent before the reference endpoint on a hill or a stair.
 if(state==2u&&cmiColumnFloor(pos)&&fract(pos.y)<.04){cmiColumnBloomInit(s,arc,b,pos,time-step);state=4u;}
 for(int transition=0;transition<3;transition++){
  float travelled=cmiColumnState(s+15u);
  if(state==2u){
   float total=cmiColumnState(arc+32u),move=min(remaining,max(0.,total-travelled));
   vec3 before=cmiColumnCurve(s,arc,b,rank,cmiColumnParameter(arc,travelled)),after=cmiColumnCurve(s,arc,b,rank,cmiColumnParameter(arc,travelled+move));
   vec3 delta=after-before;motion+=length(delta)>.000001?normalize(delta)*move:vec3(0);point=pos+motion;remaining-=move;travelled+=move;cmiColumnStore(s+15u,travelled);
   if(length(after-before)>.00001)cmiColumnStore3(arc+40u,normalize(after-before));
   if(travelled<total-.00001){marker=4.+clamp(travelled/max(total,.00001),0.,.99999);break;}
   // Reference progress never waits for collision displacement to be recovered.
   if(cmiColumnFloor(point)&&length(point.xz-cmiStarLanding(b,0u).xz)<=4.){cmiColumnBloomInit(s,arc,b,point,time-remaining/6.);state=4u;}
   else{state=5u;starStateWrite.words[arc+43u]=5u;cmiColumnStore(s+3u,time-remaining/6.);}
  }else if(state==4u){
   float r0=cmiColumnState(arc+45u),angle=cmiColumnState(arc+46u),start=cmiColumnArc(r0),total=cmiColumnArc(4.)-start;
   float move=min(remaining,max(0.,total-travelled)),r=cmiColumnRadius(start+travelled),next=cmiColumnRadius(start+travelled+move);
   motion+=vec3(cos(angle)*(next-r),cmiColumnHeight(next)-cmiColumnHeight(r),sin(angle)*(next-r));point=pos+motion;remaining-=move;travelled+=move;cmiColumnStore(s+15u,travelled);
   cmiColumnStore3(arc+40u,cmiColumnTangent(angle,next));
   if(travelled<total-.00001){marker=-1.-clamp(travelled/max(total,.00001),0.,1.);break;}
   state=5u;starStateWrite.words[arc+43u]=5u;cmiColumnStore(s+3u,time-remaining/6.);marker=0.;
  }else break;
  if(remaining<=.000001)break;
 }
 if(state==5u){
  marker=5.;float began=cmiColumnState(s+3u),age=max(0.,time-began);
  float available=max(0.,.5-max(0.,time-step-began)),move=min(remaining,available*6.);
  vec3 delta=home-point,direction=cmiColumnState3(arc+40u);
  if(length(delta)>.00001)direction=cmiColumnTurn(direction,delta,2.5*min(step,.5)*cmiColumnSmooth(clamp(age/.5,0.,1.)));
  cmiColumnStore3(arc+40u,direction);motion+=direction*move;point=pos+motion;remaining-=move;
  if(age>=.5){
   float ordinary=remaining/6.;vec3 finalVelocity=direction*6.;
   if(ordinary>0.){finalVelocity+=((home-point)*24.+(homeVelocity-finalVelocity)*6.)*ordinary;float speed=length(finalVelocity);if(speed>6.)finalVelocity*=6./speed;motion+=finalVelocity*ordinary;point=pos+motion;}
   cmiColumnExit=true;cmiColumnExitVelocity=finalVelocity;starStateWrite.words[arc+43u]=6u;phase=6u;marker=6.;target=point;feed=motion/step;return true;
  }

 }
 phase=state;target=point;feed=motion/step;return true;
}
bool cmiColumnSteer(uint a,vec3 pos,vec3 vel,vec3 home,vec3 homeVelocity,out vec3 target,out vec3 feed,out uint phase,out float marker){return cmiColumnSteerAt(a,pos,vel,home,homeVelocity,cmiStarTime(cmiStarBase(cmiStarSlot(a)),uTimeSec),uDt,target,feed,phase,marker);}
