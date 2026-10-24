// Temporary motion never changes a gather reference or a queued search's identity.
bool cmiNavigationApplied=false,cmiNavigationKnownBlocked=false,cmiNavigationCoverageUnknown=false;
vec3 cmiNavigationReference=vec3(0);uint cmiNavigationCurrentMember=131072u;
void cmiNavigationCancel(uint member){uint record=cmiNavigationRecord(member),extra=cmiNavigationExtra(member);starStateWrite.words[record+1u]=member<<15u;starStateWrite.words[extra]=0u;if((atomicAnd(starStateWrite.words[CMI_DOME_PATH_REQUESTS+member/32u],~(1u<<(member&31u)))&(1u<<(member&31u)))!=0u)atomicAdd(starStateWrite.words[CMI_DOME_PATH_CONTROL+1u],0xffffffffu);}
void cmiNavigationReturnIdentity(uint member,uint id){uint record=cmiNavigationRecord(member),extra=cmiNavigationExtra(member);if(starStateWrite.words[record]!=id||starStateWrite.words[extra]!=2u){cmiNavigationCancel(member);for(uint k=0u;k<16u;k++)starStateWrite.words[extra+k]=0u;starStateWrite.words[extra]=2u;starStateWrite.words[record]=id;starStateWrite.words[record+2u]=floatBitsToUint(-1000000.);starStateWrite.words[record+3u]=id;}}
vec3 cmiNavigationAvoid(uint member,vec3 pos,vec3 desired,float cap){
 uint extra=cmiNavigationExtra(member);float speed=length(desired);if(speed<.00001)return vec3(0);
 vec3 forward=desired/speed;float reach=min(2.,max(.25,speed*speed/36.+.1)),clear=cmiDomeCorridor(pos,forward,reach);
 if(clear>=reach)return desired;
 vec3 toGoal=cmiNavigationReference-pos,progress=length(toGoal)>.00001?normalize(toGoal):forward;
 vec3 previous=vec3(uintBitsToFloat(starStateWrite.words[extra+4u]),uintBitsToFloat(starStateWrite.words[extra+5u]),uintBitsToFloat(starStateWrite.words[extra+6u]));
 float now=cmiNavigationTime(member),next=uintBitsToFloat(starStateWrite.words[extra+7u]);vec3 best=vec3(0);float bestClear=0.,bestScore=-100.;
 if(length(previous)>.5){float distance=cmiDomeCorridor(pos,normalize(previous),reach);if(distance>=.25){best=normalize(previous);bestClear=distance;bestScore=.65*distance/reach+.25*dot(best,progress)+.1;}}
 if(cmiStormElapsed(now,next)>=0.){
  vec3 side=cross(forward,abs(forward.y)<.9?vec3(0,1,0):vec3(1,0,0));side=normalize(side);vec3 up=normalize(cross(side,forward));
  for(uint k=0u;k<6u;k++){vec3 candidate=k==0u?normalize(forward+up):k==1u?normalize(forward-up):k==2u?normalize(forward+side):k==3u?normalize(forward-side):k==4u?side:-side;
   float distance=cmiDomeCorridor(pos,candidate,reach);if(distance<.25)continue;
   float tie=float((member*1664525u+k*1013904223u)&255u)*(.001/255.);
   float score=.65*distance/reach+.25*dot(candidate,progress)+.1*dot(candidate,previous)+tie;
   if(score>bestScore+.025){best=candidate;bestClear=distance;bestScore=score;}
  }
  starStateWrite.words[extra+7u]=floatBitsToUint(now+.1);
 }
 for(uint k=0u;k<3u;k++)starStateWrite.words[extra+4u+k]=floatBitsToUint(best[k]);
 atomicAdd(starStateWrite.words[CMI_DOME_PATH_CONTROL+24u],1u);
 return best*min(min(speed,cap),sqrt(36.*max(0.,bestClear-.1)));
}
vec3 cmiNavigationSteer(uint member,vec3 pos,vec3 reference,vec3 desired,float cap){
 cmiNavigationApplied=false;cmiNavigationCurrentMember=member;cmiNavigationReference=reference;
 uint extra=cmiNavigationExtra(member);for(uint k=0u;k<3u;k++)starStateWrite.words[extra+8u+k]=floatBitsToUint(reference[k]);
 vec3 delta=reference-pos;float remaining=length(delta),reach=min(2.,remaining),clear=remaining<.00001?reach:cmiDomeCorridor(pos,delta/max(remaining,.000001),reach);
 cmiNavigationKnownBlocked=clear<reach&&!cmiDomeProbeUnknown;cmiNavigationCoverageUnknown=clear<reach&&cmiDomeProbeUnknown;
 if(clear>=reach)return desired;
 cmiNavigationApplied=true;vec3 waypoint;
 if(cmiNavigationPathFollow(member,pos,reference,waypoint)){vec3 direction=waypoint-pos;float distance=length(direction);return direction/max(distance,.000001)*min(cap,sqrt(36.*max(0.,distance-.15)));}
 return cmiNavigationAvoid(member,pos,desired,cap);
}
void cmiNavigationReturnAudit(uint member,vec3 pos,bool clipped,bool valid,float dt){
 uint record=cmiNavigationRecord(member),extra=cmiNavigationExtra(member);float blocked=uintBitsToFloat(starStateWrite.words[extra+2u]);
 bool known=valid&&!cmiNavigationCoverageUnknown&&cmiStormKnown(pos);
 blocked=known&&distance(pos,cmiNavigationReference)>.4?(clipped||cmiNavigationKnownBlocked?blocked+dt:blocked):0.;starStateWrite.words[extra+2u]=floatBitsToUint(blocked);
 uint status=starStateWrite.words[record+1u]&15u;float now=uTimeSec;
 if(known&&(clipped||cmiNavigationKnownBlocked)&&blocked>=.1&&status!=CMI_DOME_PATH_QUEUED&&status!=CMI_DOME_PATH_SEARCHING&&status!=CMI_DOME_PATH_READY&&cmiStormElapsed(now,uintBitsToFloat(starStateWrite.words[record+2u]))>=.5){
  starStateWrite.words[record+1u]=(member<<15u)|CMI_DOME_PATH_QUEUED;starStateWrite.words[record+2u]=floatBitsToUint(now);
  for(uint k=0u;k<3u;k++)starStateWrite.words[record+4u+k]=floatBitsToUint(cmiNavigationReference[k]);
  if((atomicOr(starStateWrite.words[CMI_DOME_PATH_REQUESTS+member/32u],1u<<(member&31u))&(1u<<(member&31u)))==0u)atomicAdd(starStateWrite.words[CMI_DOME_PATH_CONTROL+1u],1u);
 }
 if((starStateWrite.words[record+1u]&15u)==CMI_DOME_PATH_QUEUED){
  vec3 requested=vec3(uintBitsToFloat(starStateWrite.words[record+4u]),uintBitsToFloat(starStateWrite.words[record+5u]),uintBitsToFloat(starStateWrite.words[record+6u]));
  for(uint k=0u;k<3u;k++){starStateWrite.words[record+9u+k]=floatBitsToUint(pos[k]-4.);if(distance(requested,cmiNavigationReference)>2.)starStateWrite.words[record+4u+k]=floatBitsToUint(cmiNavigationReference[k]);}
 }
}
