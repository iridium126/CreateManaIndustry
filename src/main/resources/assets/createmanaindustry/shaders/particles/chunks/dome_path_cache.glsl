int cmiDomePathCache(uint assignment,vec3 pos,vec3 reference){
 uint record=cmiDomePathRecord(assignment),flags=state.words[record+1u];if((flags&15u)!=CMI_DOME_PATH_READY)return 0;
 float remaining=distance(pos,reference),reach=min(2.,remaining);
 if(remaining<.00001||cmiDomeCorridor(pos,(reference-pos)/remaining,reach)>=reach){state.words[record+1u]=flags&0xffff8000u;return 0;}
 uint count=(flags>>4u)&15u,index=(flags>>8u)&15u;
 vec3 base=vec3(uintBitsToFloat(state.words[record+9u]),uintBitsToFloat(state.words[record+10u]),uintBitsToFloat(state.words[record+11u]));
 vec3 waypoint=pos;for(uint step=0u;step<8u&&index<count;step++){uint pair=state.words[record+4u+index/2u];waypoint=base+cmiDomePathCell((pair>>((index&1u)*16u))&511u);if(distance(pos,waypoint)>.3)break;index++;}
 if(index>=count){state.words[record+1u]=flags&0xffff8000u;return 0;}
 reach=min(2.,distance(pos,waypoint));float clear=cmiDomeCorridor(pos,normalize(waypoint-pos),reach);
 if(clear<reach){int terrain=cmiDomeProbeUnknown?-1:1;state.words[record+1u]=(flags&0xffff8000u)|(terrain<0?CMI_DOME_PATH_UNKNOWN:CMI_DOME_PATH_IDLE);return terrain;}
 state.words[record+1u]=(flags&0xfffff0ffu)|(index<<8u);return 0;
}
