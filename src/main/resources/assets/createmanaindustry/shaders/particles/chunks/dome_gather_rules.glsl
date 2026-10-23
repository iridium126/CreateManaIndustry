bool cmiDomeGatherReady(uint ready,uint eligible,float now,float start,float deadline){
 int origin=int(floor(start*20.+.5)),ticks=max(1,int(floor(deadline*20.+.5))-origin),progress=int(floor(now*20.+.0001))-origin;
 if(progress< -1048576)progress+=2097152;else if(progress>1048576)progress-=2097152;
 if(progress<0||progress>ticks||eligible==0u||ready>eligible)return false;
 uint scale=10u*uint(ticks);return scale*ready>=(scale-uint(progress))*eligible;
}
