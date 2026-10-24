void cmiDomeSampleOffset(uint a,float deltaTime,out vec3 point,out vec3 velocity);
#pragma cmi_include chunks/dome_path_identity.glsl
bool cmiNavigationPathBlocked=false,cmiNavigationPathUnknown=false;
float cmiNavigationTime(uint member){uint extra=cmiNavigationExtra(member),a=cmiStarWord(16u+member);return starStateWrite.words[extra]==1u&&a!=0u?cmiDomeElapsed(cmiStarBase(cmiStarSlot(a)),uTimeSec):uTimeSec;}
bool cmiNavigationPathFollow(uint member,vec3 pos,vec3 reference,out vec3 waypoint){
 uint record=cmiNavigationRecord(member),extra=cmiNavigationExtra(member),flags=starStateWrite.words[record+1u];waypoint=pos;
 cmiNavigationPathBlocked=false;cmiNavigationPathUnknown=false;
 uint status=flags&15u;if(status!=CMI_DOME_PATH_READY&&status!=CMI_DOME_PATH_NO_ROUTE)return false;
 vec3 base=vec3(uintBitsToFloat(starStateWrite.words[record+9u]),uintBitsToFloat(starStateWrite.words[record+10u]),uintBitsToFloat(starStateWrite.words[record+11u]));
 vec3 goal=vec3(uintBitsToFloat(starStateWrite.words[extra+12u]),uintBitsToFloat(starStateWrite.words[extra+13u]),uintBitsToFloat(starStateWrite.words[extra+14u]));
 if(cmiStormElapsed(cmiNavigationTime(member),uintBitsToFloat(starStateWrite.words[record+8u]))>1.||any(lessThan(pos,base-vec3(.25)))||any(greaterThan(pos,base+vec3(7.25)))||(starStateWrite.words[extra]==2u&&distance(reference,goal)>2.)){starStateWrite.words[record+1u]=flags&0xffff8000u;atomicAdd(starStateWrite.words[CMI_DOME_PATH_CONTROL+25u],1u);return false;}
 if(status!=CMI_DOME_PATH_READY)return false;
 float remaining=distance(pos,reference),reach=min(2.,remaining);
 if(remaining<.00001||cmiDomeCorridor(pos,(reference-pos)/remaining,reach)>=reach){starStateWrite.words[record+1u]=flags&0xffff8000u;return false;}
 uint count=(flags>>4u)&15u,index=(flags>>8u)&15u;
 // Reconnect from the ACTUAL moving origin; never apply displacement from an obsolete search origin.
 bool connected=false;uint selected=index;
 for(uint step=0u;step<min(count,8u);step++){uint k=count-1u-step;if(k<index)break;uint pair=starStateWrite.words[record+4u+k/2u],cell=(pair>>((k&1u)*16u))&511u;vec3 candidate=base+cmiDomePathCell(cell);int terrain=cmiDomePathSegment(pos,candidate);if(terrain==0){selected=k;waypoint=candidate;connected=true;break;}}
 if(!connected){cmiNavigationPathBlocked=true;starStateWrite.words[record+1u]=flags&0xffff8000u;atomicAdd(starStateWrite.words[CMI_DOME_PATH_CONTROL+25u],1u);return false;}
 if(distance(pos,waypoint)<=.3){selected++;if(selected>=count){starStateWrite.words[record+1u]=flags&0xffff8000u;return false;}uint pair=starStateWrite.words[record+4u+selected/2u],cell=(pair>>((selected&1u)*16u))&511u;waypoint=base+cmiDomePathCell(cell);int terrain=cmiDomePathSegment(pos,waypoint);if(terrain!=0){cmiNavigationPathBlocked=terrain>0;cmiNavigationPathUnknown=terrain<0;starStateWrite.words[record+1u]=flags&0xffff8000u;return false;}}
 starStateWrite.words[record+1u]=(flags&0xfffff0ffu)|(selected<<8u);return true;
}
bool cmiDomePathFollow(uint assignment,vec3 pos,out vec3 waypoint){vec3 target,velocity;cmiDomeSampleOffset(assignment,0.,target,velocity);return cmiNavigationPathFollow(cmiNavigationMember(assignment),pos,target,waypoint);}
