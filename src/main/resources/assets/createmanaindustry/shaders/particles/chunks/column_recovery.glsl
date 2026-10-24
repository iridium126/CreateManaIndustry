layout(std430,binding=22) readonly buffer ColumnRestore {float values[];} columnRestore;
uniform int uColumnRestoreCount;
uniform ivec4 uColumnRestoreSlots,uColumnRestoreTokens;
uniform vec4 uColumnRestoreTimes;
void cmiColumnReplayCollision(inout vec3 pos,inout vec3 vel,float step){
 vec4 matMotion=vec4(0,1,0,0);bool isModel=true,cmiContactSweepValid=true,cmiTerrainClipped=false;
 #define CMI_COLUMN_REPLAY
 #define uDt step
 #pragma cmi_include chunks/collision_sweep.glsl
 #undef uDt
 #undef CMI_COLUMN_REPLAY
 if(!swept)pos+=vel*step;
}
void cmiColumnRecover(uint assignment,inout vec3 pos,inout vec3 vel,vec3 home,vec3 homeVelocity){
 uint slot=cmiStarSlot(assignment),rank=cmiStarRank(assignment),waveBase=cmiStarBase(slot),stateBase=262592u+(slot-4u)*1024u+rank*16u,arc=328128u+((slot-4u)*64u+rank)*CMI_COLUMN_ARC_WORDS,id=uint(cmiStarVec(waveBase).x+.5);
 int selected=-1;for(int entry=0;entry<uColumnRestoreCount;entry++)if(uint(uColumnRestoreSlots[entry])==slot)selected=entry;
 bool incoming=selected>=0&&CMI_STATE(starStateRead,arc+CMI_COLUMN_TOKEN)!=uint(uColumnRestoreTokens[selected]);
 bool catching=CMI_STATE(starStateRead,stateBase)==id&&CMI_STATE(starStateRead,arc+CMI_COLUMN_CATCHUP)!=0u;
 if(!incoming&&!catching)return;
 for(uint field=0u;field<16u;field++)CMI_STATE(starStateWrite,stateBase+field)=CMI_STATE(starStateRead,stateBase+field);
 for(uint field=0u;field<CMI_COLUMN_ARC_WORDS;field++)CMI_STATE(starStateWrite,arc+field)=CMI_STATE(starStateRead,arc+field);
 cmiColumnRestored=true;cmiColumnRecoveryFrame=true;
 if(incoming){
  uint offset=(uint(selected)*64u+rank)*30u;vec3 origin=cmiStarLanding(waveBase,0u).xyz;
  for(uint axis=0u;axis<3u;axis++){pos[axis]=origin[axis]+columnRestore.values[offset+1u+axis];vel[axis]=columnRestore.values[offset+4u+axis];}
  uint phase=uint(columnRestore.values[offset]);
  for(uint field=0u;field<16u;field++)CMI_STATE(starStateWrite,stateBase+field)=0u;
  for(uint field=0u;field<CMI_COLUMN_ARC_WORDS;field++)CMI_STATE(starStateWrite,arc+field)=0u;
  CMI_STATE(starStateWrite,stateBase)=id;
  if(phase==2u){vec3 start,direction;for(uint axis=0u;axis<3u;axis++){start[axis]=origin[axis]+columnRestore.values[offset+17u+axis];direction[axis]=columnRestore.values[offset+20u+axis];}cmiColumnCurveInit(stateBase,arc,waveBase,rank,start,direction,columnRestore.values[offset+7u]);}
  for(uint axis=0u;axis<3u;axis++){
   cmiColumnStore(stateBase+4u+axis,origin[axis]+columnRestore.values[offset+11u+axis]);cmiColumnStore(stateBase+8u+axis,columnRestore.values[offset+14u+axis]);
   cmiColumnStore(arc+CMI_COLUMN_TANGENT+axis,columnRestore.values[offset+23u+axis]);
  }
  cmiColumnStore(stateBase+1u,columnRestore.values[offset+8u]);cmiColumnStore(stateBase+2u,columnRestore.values[offset+7u]);cmiColumnStore(stateBase+3u,columnRestore.values[offset+9u]);cmiColumnStore(stateBase+15u,columnRestore.values[offset+10u]);
  cmiColumnStore(arc+CMI_COLUMN_BLOOM_RADIUS,columnRestore.values[offset+26u]);cmiColumnStore(arc+CMI_COLUMN_BLOOM_ANGLE,columnRestore.values[offset+27u]);
  CMI_STATE(starStateWrite,arc+CMI_COLUMN_PHASE)=phase;CMI_STATE(starStateWrite,arc+CMI_COLUMN_TOKEN)=uint(uColumnRestoreTokens[selected]);
  cmiColumnStore(stateBase+7u,cmiStarTime(waveBase,uColumnRestoreTimes[selected]));
 }
 float cursor=cmiColumnState(stateBase+7u),now=cmiStarTime(waveBase,uTimeSec);uint phase=CMI_STATE(starStateWrite,arc+CMI_COLUMN_PHASE);
 for(int replay=0;replay<4&&cursor<now-.0001;replay++){
  float step=min(.1,now-cursor);vec3 target,feed;float marker;
  bool engaged=cmiColumnSteerAt(assignment,pos,vel,home,homeVelocity,cursor+step,step,target,feed,phase,marker);
  if(engaged)vel=feed;else vel+=((home-pos)*24.+(homeVelocity-vel)*6.)*step;
  float speed=length(vel),cap=phase==1u?CMI_STORM_INTERCEPT:CMI_STORM_CRUISE;if(speed>cap)vel*=cap/speed;
  cmiColumnReplayCollision(pos,vel,step);cursor+=step;
 }
 cmiColumnStore(stateBase+7u,cursor);CMI_STATE(starStateWrite,arc+CMI_COLUMN_CATCHUP)=cursor<now-.0001?1u:0u;
 cmiColumnExit=true;cmiColumnExitVelocity=vel;
}
