void cmiDomePathIdentity(uint assignment,uint member){
 uint record=cmiDomePathRecord(assignment),id=uint(cmiStarVec(cmiStarBase(cmiStarSlot(assignment))).x+.5);
 uint pause=floatBitsToUint(cmiDomePause(assignment));uint extra=cmiNavigationExtra(member);
 if(CMI_STATE(starStateWrite,extra)!=1u||CMI_STATE(starStateWrite,record)!=id||CMI_STATE(starStateWrite,record+3u)!=pause||(CMI_STATE(starStateWrite,record+1u)>>15u)!=member){
  for(uint k=0u;k<16u;k++)CMI_STATE(starStateWrite,extra+k)=0u;CMI_STATE(starStateWrite,extra)=1u;
  CMI_STATE(starStateWrite,record)=id;CMI_STATE(starStateWrite,record+1u)=member<<15u;
  CMI_STATE(starStateWrite,record+2u)=floatBitsToUint(-1000000.);CMI_STATE(starStateWrite,record+3u)=pause;
  uint rank=member;
  if((atomicAnd(CMI_STATE(starStateWrite,CMI_DOME_PATH_REQUESTS+rank/32u),~(1u<<(rank&31u)))&(1u<<(rank&31u)))!=0u)atomicAdd(CMI_STATE(starStateWrite,CMI_DOME_PATH_CONTROL+1u),0xffffffffu);
 }
}
