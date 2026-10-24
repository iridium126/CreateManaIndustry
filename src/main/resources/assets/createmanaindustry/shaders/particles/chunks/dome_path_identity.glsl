void cmiDomePathIdentity(uint assignment,uint member){
 uint record=cmiDomePathRecord(assignment),id=uint(cmiStarVec(cmiStarBase(cmiStarSlot(assignment))).x+.5);
 uint pause=floatBitsToUint(cmiDomePause(assignment));uint extra=cmiNavigationExtra(member);
 if(starStateWrite.words[extra]!=1u||starStateWrite.words[record]!=id||starStateWrite.words[record+3u]!=pause||(starStateWrite.words[record+1u]>>15u)!=member){
  for(uint k=0u;k<16u;k++)starStateWrite.words[extra+k]=0u;starStateWrite.words[extra]=1u;
  starStateWrite.words[record]=id;starStateWrite.words[record+1u]=member<<15u;
  starStateWrite.words[record+2u]=floatBitsToUint(-1000000.);starStateWrite.words[record+3u]=pause;
  uint rank=member;
  if((atomicAnd(starStateWrite.words[CMI_DOME_PATH_REQUESTS+rank/32u],~(1u<<(rank&31u)))&(1u<<(rank&31u)))!=0u)atomicAdd(starStateWrite.words[CMI_DOME_PATH_CONTROL+1u],0xffffffffu);
 }
}
