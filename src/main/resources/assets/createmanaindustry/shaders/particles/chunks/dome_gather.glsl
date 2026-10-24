// Gather-only body queries. Unknown sections never latch a flow pause.
bool cmiDomeNavigationBlocked=false,cmiDomeNavigationUnknown=false,cmiDomeProbeUnknown=false,cmiDomeNavigationClipped=false;
int cmiDomeBodyTerrain(vec3 feet){
 ivec3 lo=ivec3(floor(feet+vec3(-.175,.001,-.175))),hi=ivec3(floor(feet+vec3(.175,.599,.175)));
 // Whole-air/solid section markers are common; avoid eight voxel reads.
 bool samePage=all(equal(lo>>4,hi>>4));int sharedPage=samePage?cmiStormLookupPage(lo>>4):-4;
 if(samePage){if(sharedPage==-2)return 0;if(sharedPage==-3)return 1;if(sharedPage==-1)return -1;}
 bool unknown=false,occupied=false;
 for(int x=lo.x;x<=hi.x;x++)for(int y=lo.y;y<=hi.y;y++)for(int z=lo.z;z<=hi.z;z++){
  ivec3 cell=ivec3(x,y,z);int page=samePage?sharedPage:cmiStormLookupPage(cell>>4);
  if(page==-1){unknown=true;continue;}occupied=occupied||page==-3||(page>=0&&cmiStormSolidInPage(cell,page));
 }
 return occupied?1:unknown?-1:0;
}
float cmiDomeCorridor(vec3 pos,vec3 direction,float reach){
 cmiDomeProbeUnknown=false;float clear=0.;int steps=min(8,int(ceil(reach*4.)));for(int k=1;k<=steps;k++){float d=min(float(k)*.25,reach);int terrain=cmiDomeBodyTerrain(pos+direction*d);if(terrain!=0){cmiDomeProbeUnknown=terrain<0;break;}clear=d;}return clear;
}
