// Run after physical movement at the shared animation tick; no renderer or CPU member updates.
bool cmiAllayLookVisible(vec3 eye,vec3 target){
 if(cmiStormVoxel){vec3 d=target-eye;float distance=length(d);int steps=min(32,max(1,int(ceil(distance*4.))));for(int i=1;i<steps;i++){vec3 point=eye+d*(float(i)/float(steps));if(cmiStormKnown(point)&&cmiStormSolid(point))return false;}}
 else if(uCollisionOn==1){vec3 delta=target-eye;int steps=min(32,max(1,int(ceil(length(delta)*4.))));for(int i=1;i<steps;i++){vec3 point=eye+delta*(float(i)/float(steps));if(solidAt(point,point+vec3(64,0,0),0))return false;}}
 return true;
}
int cmiAllayNearestVisible(vec3 position,vec3 eye){
 int best=-1;float nearest=CMI_ALLAY_LOOK_RANGE_SQ;
 // Only on a look opportunity (30..60 ticks), never once per vertex/frame.
 for(int i=0;i<uPlayerCount;i++){vec3 delta=position-cmiPlayerFeet(i);float q=dot(delta,delta);if(q<=nearest&&(best<0||q<nearest||cmiPlayerId(i)<cmiPlayerId(best))&&cmiAllayLookVisible(eye,cmiPlayerEyes(i))){nearest=q;best=i;}}
 return best;
}
void cmiUpdateAllayAnimation(uint src,uint dst,vec4 p0,vec4 p1,vec4 p3,vec3 position,vec3 velocity,float age,bool storm,bool corpse,int animation,float carry,bool overrideBody,float bodyYaw){
 if(!uAllayAnimationWriteEnabled||dst*4u+3u>=uint(imageSize(cmi_AllayAnimationWrite)))return;
 uvec4 a=cmiAnimationReadRow(src*4u),b=cmiAnimationReadRow(src*4u+1u),c=cmiAnimationReadRow(src*4u+2u),anchor=cmiAnimationReadRow(src*4u+3u);
 if((c.z&0x80000000u)==0u){cmiInitializeAllayAnimation(dst,p0.xyz,p1.xyz,p3.z);memoryBarrierImage();a=cmiAnimationWrittenRow(dst*4u);b=cmiAnimationWrittenRow(dst*4u+1u);c=cmiAnimationWrittenRow(dst*4u+2u);anchor=cmiAnimationWrittenRow(dst*4u+3u);}
 uint tick=uAllayAnimationClockEnabled?uAllayAnimationTick:uint(floor(max(0.,age)*20.+.00001)),n=uAllayAnimationClockEnabled?tick-c.w:tick>=c.w?tick-c.w:0u;
 vec2 old=cmiAnimationAngles(a.y),head=cmiAnimationHead(a.w);float oldCarry=cmiAnimationUnpack(b.z).y;
 bool wasDead=(c.z&0x40000000u)!=0u;
 if(n>0u||corpse!=wasDead){
  float ticks=float(n);a.x=a.y;a.z=a.w;b.x=floatBitsToUint(mod(uintBitsToFloat(b.y),CMI_ANIM_TAU));
  // Sample the last completed animation tick instead of the enclosing render frame.
  vec3 samplePosition=position-velocity*((uAllayAnimationClockEnabled?uAllayAnimationFraction:clamp(age*20.-float(tick),0.,1.))/20.);
  vec3 displacement=samplePosition-uintBitsToFloat(anchor.xyz);float horizontal=length(displacement.xz);
  vec2 walk=corpse?vec2(0):cmiAnimationWalk(head.y,min(horizontal/max(ticks,1.)*4.,1.),ticks);
  float yaw=old.x,pitch=0.,worldHead=yaw+head.x;bool moving=horizontal/max(ticks,1.)>.0005;
  if(!corpse){if(overrideBody)yaw=bodyYaw;else if(moving&&length(velocity.xz)>.0001)yaw=cmiAnimationTurn(yaw,atan(-velocity.x,velocity.z),1.570796327*ticks);}
  float size=emitters.u[floatBitsToUint(p3.w)*VEC4_PER_EMITTER+5u].z*2.*(storm?1.:p0.w);vec3 eye=position+vec3(0,max(.01,size*.6),0),look=vec3(0);bool looking=false;
  uint assignment=storm?cmiStarAssignment(p0.w):0u;uint member=cmiStarMember(p0.w);uint phase=assignment!=0u&&member<131072u?CMI_STATE(starStateWrite,member*2u+1u)&255u:6u;
  if(!corpse&&assignment!=0u&&phase<6u){vec4 target=cmiAttackEyes(cmiStarSlot(assignment));if(target.w>.5){look=target.xyz;looking=true;}}
  int player=cmiPlayerWithId(int(c.x));
  if(player>=0){vec3 delta=position-cmiPlayerFeet(player);if(dot(delta,delta)>CMI_ALLAY_LOOK_RANGE_SQ||!cmiAllayLookVisible(eye,cmiPlayerEyes(player)))player=-1;}
  if(!corpse&&!looking){
   bool expired=int(tick-c.y)>=0;
   if(c.x!=0u&&(player<0||expired)){c.x=0u;c.y=tick+30u+uint(cmiHash1(p3.z+float(tick)*.037)*31.);}
   else if(c.x==0u&&expired){player=cmiAllayNearestVisible(position,eye);if(player>=0){c.x=uint(cmiPlayerId(player));c.y=tick+45u+uint(cmiHash1(p3.z+float(tick)*.071)*46.);}else c.y=tick+30u+uint(cmiHash1(p3.z+float(tick)*.037)*31.);}
   if(c.x!=0u&&player>=0){look=cmiPlayerEyes(player);looking=true;}
  }
  if(looking){vec3 delta=look-eye;float targetYaw=length(delta.xz)>.00001?atan(-delta.x,delta.z):worldHead;worldHead=cmiAnimationTurn(worldHead,targetYaw,.174532925*ticks);pitch=clamp(-atan(delta.y,max(length(delta.xz),.000001)),-.698131701,.698131701);}
  else if(!corpse)worldHead=cmiAnimationTurn(worldHead,yaw,.174532925*ticks);
  float relative=cmiAnimationAngle(yaw,worldHead);
  uint stable=c.z&255u;
  if(corpse)relative=head.x;
  else if(moving||overrideBody){relative=clamp(relative,-1.308996939,1.308996939);stable=0u;}
  else {float last=cmiAnimationAngles((c.z>>8u)&65535u).x;if(abs(cmiAnimationAngle(last,worldHead))>.261799388){stable=0u;last=worldHead;}else stable=min(255u,stable+n);float limit=1.308996939*(1.-clamp((float(stable)-10.)/10.,0.,1.));yaw=worldHead+clamp(cmiAnimationAngle(worldHead,yaw),-limit,limit);relative=cmiAnimationAngle(yaw,worldHead);c.z=(c.z&0xff0000ffu)|((cmiAnimationAngles(last,0.)&65535u)<<8u);}
  a.y=cmiAnimationAngles(yaw,corpse?old.y:pitch);a.w=cmiAnimationHead(relative,walk.x);
  b.y=floatBitsToUint(uintBitsToFloat(b.x)+walk.y);if(corpse){b.x=0u;b.y=0u;}b.z=cmiAnimationPack(vec2(oldCarry,clamp(oldCarry+(carry>oldCarry?1.:-1.)*ticks/5.,min(carry,oldCarry),max(carry,oldCarry))));
  uint oldDance=(b.w>>6u)&63u,base=oldDance==63u?0u:oldDance,total=base+n;
  bool cycled=animation==1&&!corpse&&(((b.w&0x100000u)!=0u&&oldDance!=63u)||total>=55u);
  uint dance=animation==1&&!corpse?total%55u:63u,previousDance=oldDance;
  float previousSpin=float((b.w>>16u)&15u);
  if(n>1u){
   float prior=ticks-1.;vec2 previousWalk=corpse?vec2(0):cmiAnimationWalk(head.y,min(horizontal/ticks*4.,1.),prior);
   float ratio=prior/ticks,priorYaw=old.x+cmiAnimationAngle(old.x,yaw)*ratio;
   a.x=cmiAnimationAngles(priorYaw,corpse?old.y:pitch);a.z=cmiAnimationHead(head.x+cmiAnimationAngle(head.x,relative)*ratio,previousWalk.x);
   float oldPhase=uintBitsToFloat(b.x);b.x=floatBitsToUint(oldPhase+previousWalk.y);
   b.z=cmiAnimationPack(vec2(clamp(oldCarry+(carry>oldCarry?1.:-1.)*prior/5.,min(carry,oldCarry),max(carry,oldCarry)),cmiAnimationUnpack(b.z).y));
   if(dance!=63u){previousDance=(total-1u)%55u;bool previousCycled=((b.w&0x100000u)!=0u&&oldDance!=63u)||total-1u>=55u;previousSpin=cmiAnimationSpin(float(previousDance),previousCycled);}
  }
  b.w=previousDance|(dance<<6u)|(uint(previousSpin)<<12u)|(uint(dance==63u?0.:cmiAnimationSpin(float(dance),cycled))<<16u)|(cycled?0x100000u:0u);
  if(corpse){b.x=0u;b.y=0u;}
  c.z=(c.z&0x00ffff00u)|stable|0x80000000u|(corpse?0x40000000u:0u);c.w=tick;anchor.xyz=floatBitsToUint(samplePosition);
 }
 cmiAnimationStore(dst*4u,a);cmiAnimationStore(dst*4u+1u,b);cmiAnimationStore(dst*4u+2u,c);cmiAnimationStore(dst*4u+3u,anchor);
}
