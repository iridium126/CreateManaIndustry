// Gather-only body queries. Unknown sections never latch a flow pause.
bool cmiDomeNavigationBlocked=false,cmiDomeNavigationUnknown=false,cmiDomeProbeUnknown=false,cmiDomeNavigationClipped=false;
int cmiDomeBodyTerrain(vec3 feet){
 CmiStormBody body=cmiStormBody(feet);return !body.complete?-1:body.occupied?1:0;
}
float cmiDomeCorridor(vec3 pos,vec3 direction,float reach){
 cmiDomeProbeUnknown=false;float clear=0.;int steps=min(8,int(ceil(reach*4.)));for(int k=1;k<=steps;k++){float d=min(float(k)*.25,reach);int terrain=cmiDomeBodyTerrain(pos+direction*d);if(terrain!=0){cmiDomeProbeUnknown=terrain<0;break;}clear=d;}return clear;
}
