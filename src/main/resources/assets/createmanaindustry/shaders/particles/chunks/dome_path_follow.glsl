void cmiDomePathIdentity(uint assignment,uint member){
 uint record=cmiDomePathRecord(assignment),id=uint(cmiStarVec(cmiStarBase(cmiStarSlot(assignment))).x+.5);
 uint pause=floatBitsToUint(cmiDomePause(assignment));
 if(starStateWrite.words[record]!=id||starStateWrite.words[record+3u]!=pause||(starStateWrite.words[record+1u]>>15u)!=member){
  starStateWrite.words[record]=id;starStateWrite.words[record+1u]=member<<15u;
  starStateWrite.words[record+2u]=floatBitsToUint(-1000000.);starStateWrite.words[record+3u]=pause;
  uint rank=cmiStarSlot(assignment)*12288u+cmiMajorRank(assignment);
  if((atomicAnd(starStateWrite.words[CMI_DOME_PATH_REQUESTS+rank/32u],~(1u<<(rank&31u)))&(1u<<(rank&31u)))!=0u)atomicAdd(starStateWrite.words[CMI_DOME_PATH_CONTROL+1u],0xffffffffu);
 }
}
bool cmiDomePathFollow(uint assignment,vec3 pos,out vec3 waypoint){
 uint record=cmiDomePathRecord(assignment),flags=starStateWrite.words[record+1u];waypoint=pos;
 if((flags&15u)!=CMI_DOME_PATH_READY)return false;
 float now=cmiDomeElapsed(cmiStarBase(cmiStarSlot(assignment)),uTimeSec);
 if(now-uintBitsToFloat(starStateWrite.words[record+8u])>1.){starStateWrite.words[record+1u]=flags&0xffff8000u;return false;}
 uint count=(flags>>4u)&15u,index=(flags>>8u)&15u;
 vec3 base=vec3(uintBitsToFloat(starStateWrite.words[record+9u]),uintBitsToFloat(starStateWrite.words[record+10u]),uintBitsToFloat(starStateWrite.words[record+11u]));
 if(index>=count){starStateWrite.words[record+1u]=flags&0xffff8000u;return false;}
 uint pair=starStateWrite.words[record+4u+index/2u],cell=(pair>>((index&1u)*16u))&511u;
 waypoint=base+cmiDomePathCell(cell);
 if(distance(pos,waypoint)<=.3){index++;if(index>=count){starStateWrite.words[record+1u]=flags&0xffff8000u;return false;}pair=starStateWrite.words[record+4u+index/2u];cell=(pair>>((index&1u)*16u))&511u;waypoint=base+cmiDomePathCell(cell);}
 starStateWrite.words[record+1u]=(flags&0xfffff0ffu)|(index<<8u);return true;
}
