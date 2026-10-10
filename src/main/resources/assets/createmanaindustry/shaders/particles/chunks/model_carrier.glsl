#pragma cmi_include chunks/allay_storm.glsl
uniform float uWaveTier[4];
bool cmiModelCarrier(bool storm,float hp,float seed,float headerCarrier){
    if(!storm)return headerCarrier>.5;
    if(hp<=0.0)return false;
    int k=cmiStormWaveClaim(seed);return k>=0&&uWaveTier[k]>.5;
}
