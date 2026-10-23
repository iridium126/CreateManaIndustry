bool cmiDomeGatherAudit(uint assignment,uint member,vec3 pos,vec3 target,int terrain,inout float marker){
 uint rank=cmiStarSlot(assignment)*12288u+cmiMajorRank(assignment),window=CMI_DOME_GATHER_WINDOW+rank*8u;
 uint id=state.words[member*2u],tag=id|0x80000000u,record=CMI_DOME_PATH_BASE+rank*12u;
 uint pause=floatBitsToUint(cmiDomePause(assignment)),frame=state.words[window+7u];
 float error=distance(pos,target);
 int cached=cmiDomePathCache(assignment,pos,target);bool valid=(frame&6u)==0u&&terrain>=0&&cached>=0;
 bool same=starStateRead.words[window]==tag&&starStateRead.words[window+5u]==pause;
 float elapsed=same?uintBitsToFloat(starStateRead.words[window+1u]):0.;
 float initialError=same?uintBitsToFloat(starStateRead.words[window+2u]):error;
 float blocked=same?uintBitsToFloat(starStateRead.words[window+3u]):0.;
 float occupied=same?uintBitsToFloat(starStateRead.words[window+4u]):0.;
 float evidence=same?uintBitsToFloat(starStateRead.words[window+6u]):0.;
 bool clipped=(frame&1u)!=0u||cached>0;
 occupied=valid&&terrain==1&&!isinf(uintBitsToFloat(pause))?occupied+uDt:0.;bool retire=occupied>=.5;
 if(!valid||error<=.4){elapsed=0.;blocked=0.;evidence=0.;initialError=error;}
 else{
  if(clipped)evidence+=uDt;
  if(elapsed>0.||clipped){if(elapsed==0.)initialError=error;elapsed+=uDt;if(clipped)blocked+=uDt;}
  uint status=state.words[record+1u]&15u;
  bool searched=state.words[record]==id&&state.words[record+3u]==pause&&status==CMI_DOME_PATH_NO_ROUTE&&state.words[record+4u]==uStormCollisionRevision
   &&cmiDomeElapsed(cmiStarBase(cmiStarSlot(assignment)),uTimeSec)-uintBitsToFloat(state.words[record+8u])<=1.;
  retire=retire||(elapsed>=.5&&blocked>=.1&&initialError-error<.05&&searched);
  if(elapsed>=.5){elapsed=0.;blocked=0.;initialError=error;}
  float now=cmiDomeElapsed(cmiStarBase(cmiStarSlot(assignment)),uTimeSec);
  bool staleResult=(status==CMI_DOME_PATH_NO_ROUTE||status==CMI_DOME_PATH_READY)&&now-uintBitsToFloat(state.words[record+8u])>1.||status==CMI_DOME_PATH_NO_ROUTE&&state.words[record+4u]!=uStormCollisionRevision;
  if(!retire&&clipped&&evidence>=.1&&status!=CMI_DOME_PATH_QUEUED&&status!=CMI_DOME_PATH_SEARCHING
   &&(status!=CMI_DOME_PATH_READY&&status!=CMI_DOME_PATH_NO_ROUTE||staleResult)&&now-uintBitsToFloat(state.words[record+2u])>=.5){
   state.words[record]=id;state.words[record+1u]=(member<<15u)|CMI_DOME_PATH_QUEUED;
   state.words[record+2u]=floatBitsToUint(now);state.words[record+3u]=pause;
   for(uint axis=0u;axis<3u;axis++){state.words[record+4u+axis]=floatBitsToUint(target[axis]);state.words[record+9u+axis]=floatBitsToUint(pos[axis]-4.);}
   if((atomicOr(state.words[CMI_DOME_PATH_REQUESTS+rank/32u],1u<<(rank&31u))&(1u<<(rank&31u)))==0u)atomicAdd(state.words[CMI_DOME_PATH_CONTROL+1u],1u);
  }
 }
 state.words[window]=tag;state.words[window+1u]=floatBitsToUint(elapsed);state.words[window+2u]=floatBitsToUint(initialError);
 state.words[window+3u]=floatBitsToUint(blocked);state.words[window+4u]=floatBitsToUint(occupied);state.words[window+5u]=pause;state.words[window+6u]=floatBitsToUint(evidence);
 if((state.words[record+1u]&15u)==CMI_DOME_PATH_QUEUED)for(uint axis=0u;axis<3u;axis++)state.words[record+9u+axis]=floatBitsToUint(pos[axis]-4.);
 if(!valid){state.words[record+1u]=(member<<15u)|CMI_DOME_PATH_UNKNOWN;if((atomicAnd(state.words[CMI_DOME_PATH_REQUESTS+rank/32u],~(1u<<(rank&31u)))&(1u<<(rank&31u)))!=0u)atomicAdd(state.words[CMI_DOME_PATH_CONTROL+1u],0xffffffffu);}
 if(!retire)return false;
 uint word=rank/32u,bit=1u<<(rank&31u);
 atomicAnd(state.words[CMI_GATHER_READY+word],~bit);atomicAnd(state.words[CMI_GATHER_ELIGIBLE+word],~bit);
 CMI_DOME_COMPLETE(state,member,word,bit,false,marker);
 for(uint component=0u;component<8u;component++)state.words[window+component]=0u;
 state.words[record+1u]=member<<15u;if((atomicAnd(state.words[CMI_DOME_PATH_REQUESTS+word],~bit)&bit)!=0u)atomicAdd(state.words[CMI_DOME_PATH_CONTROL+1u],0xffffffffu);return true;
}
