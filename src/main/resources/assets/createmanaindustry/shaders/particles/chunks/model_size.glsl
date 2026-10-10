// Shared MODEL size semantics: storm p0.w is identity, not a scale multiplier.
float cmiModelSize(vec4 p0,vec4 p3,float start,float end,float ease,bool storm){
    float life=clamp(p3.x/max(p3.y,1e-5),0.0,1.0);
    return mix(start,end,pow(life,max(ease,.001)))*(storm?1.0:p0.w);
}
