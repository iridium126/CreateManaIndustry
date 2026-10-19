#pragma cmi_include chunks/allay_storm.glsl
uniform float uWaveTier[4];
bool cmiModelCarrier(bool storm,float hp,float seed,float identity,float headerCarrier,float marker){
    if(!storm)return headerCarrier>.5;
    if(hp<=0.0)return false;
    int k=cmiStormWaveClaim(seed,identity);return k>=0&&(uStarfallEnabled?cmiStarTier(k):uWaveTier[k])>.5&&(!uStarfallEnabled||cmiAttackCarry(identity,uTimeSec,marker)>0.);
}

bool cmiModelCarrier(bool storm,float hp,float seed,float identity,float headerCarrier){return cmiModelCarrier(storm,hp,seed,identity,headerCarrier,0.);}
