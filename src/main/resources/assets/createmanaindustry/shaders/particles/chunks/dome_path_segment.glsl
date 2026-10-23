int cmiDomePathSegment(vec3 start,vec3 end){
 float span=distance(start,end);int steps=max(1,int(ceil(span/.25)));if(steps>56)return 1;
 for(int step=1;step<=steps;step++){int terrain=cmiDomeBodyTerrain(mix(start,end,float(step)/float(steps)));if(terrain!=0)return terrain;}
 return 0;
}
