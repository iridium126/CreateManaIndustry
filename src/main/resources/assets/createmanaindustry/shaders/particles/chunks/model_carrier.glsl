#pragma cmi_include chunks/allay_storm.glsl
uniform float uWaveTier[4];
bool cmiModelCarrier(bool storm,float hp,float seed,float identity,float headerCarrier){
    if(!storm)return headerCarrier>.5;
    if(hp<=0.0)return false;
    int k=cmiStormWaveClaim(seed,identity);return k>=0&&(uStarfallEnabled?cmiStarTier(k):uWaveTier[k])>.5&&(!uStarfallEnabled||!cmiDome(cmiStarBase(uint(k)))||cmiAttackCarry(identity,uTimeSec)>0.);
}
