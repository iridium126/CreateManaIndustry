bool cmiDomeGatherAudit(uint assignment,uint member,vec3 pos,vec3 target,int terrain,inout float marker){
 uint rank=cmiStarSlot(assignment)*12288u+cmiMajorRank(assignment),window=CMI_DOME_GATHER_WINDOW+rank*8u;
 uint id=CMI_STATE(state,member*2u),tag=id|0x80000000u,record=cmiNavigationRecord(member);
 uint pause=floatBitsToUint(cmiDomePause(assignment)),frame=CMI_STATE(state,window+7u);
 float error=distance(pos,target);
 int cached=cmiDomePathCache(assignment,pos,target);bool valid=(frame&6u)==0u&&terrain>=0&&cached>=0;
 bool same=CMI_STATE(starStateRead,window)==tag&&CMI_STATE(starStateRead,window+5u)==pause;
 float elapsed=same?uintBitsToFloat(CMI_STATE(starStateRead,window+1u)):0.;
 float initialError=same?uintBitsToFloat(CMI_STATE(starStateRead,window+2u)):error;
 float blocked=same?uintBitsToFloat(CMI_STATE(starStateRead,window+3u)):0.;
 float occupied=same?uintBitsToFloat(CMI_STATE(starStateRead,window+4u)):0.;
 float evidence=same?uintBitsToFloat(CMI_STATE(starStateRead,window+6u)):0.;
 bool clipped=(frame&1u)!=0u||cached>0;
 occupied=valid&&terrain==1&&!isinf(uintBitsToFloat(pause))?occupied+uDt:0.;bool retire=occupied>=.5;
 if(!valid||error<=.4){elapsed=0.;blocked=0.;evidence=0.;initialError=error;}
 else{
  if(clipped)evidence+=uDt;
  if(elapsed>0.||clipped){if(elapsed==0.)initialError=error;elapsed+=uDt;if(clipped)blocked+=uDt;}
  uint status=CMI_STATE(state,record+1u)&15u;
  bool searched=CMI_STATE(state,record)==id&&CMI_STATE(state,record+3u)==pause&&status==CMI_DOME_PATH_NO_ROUTE&&CMI_STATE(state,record+4u)==uStormCollisionRevision
   &&cmiDomeElapsed(cmiStarBase(cmiStarSlot(assignment)),uTimeSec)-uintBitsToFloat(CMI_STATE(state,record+8u))<=1.;
  retire=retire||(elapsed>=.5&&blocked>=.1&&initialError-error<.05&&searched);
  if(elapsed>=.5){elapsed=0.;blocked=0.;initialError=error;}
  float now=cmiDomeElapsed(cmiStarBase(cmiStarSlot(assignment)),uTimeSec);
  bool staleResult=(status==CMI_DOME_PATH_NO_ROUTE||status==CMI_DOME_PATH_READY)&&now-uintBitsToFloat(CMI_STATE(state,record+8u))>1.||status==CMI_DOME_PATH_NO_ROUTE&&CMI_STATE(state,record+4u)!=uStormCollisionRevision;
  if(!retire&&clipped&&evidence>=.1&&status!=CMI_DOME_PATH_QUEUED&&status!=CMI_DOME_PATH_SEARCHING
   &&(status!=CMI_DOME_PATH_READY&&status!=CMI_DOME_PATH_NO_ROUTE||staleResult)&&now-uintBitsToFloat(CMI_STATE(state,record+2u))>=.5){
   CMI_STATE(state,record)=id;CMI_STATE(state,record+1u)=(member<<15u)|CMI_DOME_PATH_QUEUED;
   CMI_STATE(state,record+2u)=floatBitsToUint(now);CMI_STATE(state,record+3u)=pause;
   for(uint axis=0u;axis<3u;axis++){CMI_STATE(state,record+4u+axis)=floatBitsToUint(target[axis]);CMI_STATE(state,record+9u+axis)=floatBitsToUint(pos[axis]-4.);}
   if((atomicOr(CMI_STATE(state,CMI_DOME_PATH_REQUESTS+member/32u),1u<<(member&31u))&(1u<<(member&31u)))==0u)atomicAdd(CMI_STATE(state,CMI_DOME_PATH_CONTROL+1u),1u);
  }
 }
 CMI_STATE(state,window)=tag;CMI_STATE(state,window+1u)=floatBitsToUint(elapsed);CMI_STATE(state,window+2u)=floatBitsToUint(initialError);
 CMI_STATE(state,window+3u)=floatBitsToUint(blocked);CMI_STATE(state,window+4u)=floatBitsToUint(occupied);CMI_STATE(state,window+5u)=pause;CMI_STATE(state,window+6u)=floatBitsToUint(evidence);
 if((CMI_STATE(state,record+1u)&15u)==CMI_DOME_PATH_QUEUED)for(uint axis=0u;axis<3u;axis++)CMI_STATE(state,record+9u+axis)=floatBitsToUint(pos[axis]-4.);
 if(!valid){CMI_STATE(state,record+1u)=(member<<15u)|CMI_DOME_PATH_UNKNOWN;if((atomicAnd(CMI_STATE(state,CMI_DOME_PATH_REQUESTS+member/32u),~(1u<<(member&31u)))&(1u<<(member&31u)))!=0u)atomicAdd(CMI_STATE(state,CMI_DOME_PATH_CONTROL+1u),0xffffffffu);}
 if(!retire)return false;
 uint word=rank/32u,bit=1u<<(rank&31u);
 atomicAnd(CMI_STATE(state,CMI_GATHER_READY+word),~bit);atomicAnd(CMI_STATE(state,CMI_GATHER_ELIGIBLE+word),~bit);
 CMI_DOME_COMPLETE(state,member,word,bit,false,marker);
 for(uint component=0u;component<8u;component++)CMI_STATE(state,window+component)=0u;
 CMI_STATE(state,record+1u)=member<<15u;if((atomicAnd(CMI_STATE(state,CMI_DOME_PATH_REQUESTS+member/32u),~(1u<<(member&31u)))&(1u<<(member&31u)))!=0u)atomicAdd(CMI_STATE(state,CMI_DOME_PATH_CONTROL+1u),0xffffffffu);return true;
}
