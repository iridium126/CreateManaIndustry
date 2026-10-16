// Pure geometry helpers, shared by preparation, emission and real-driver tests.
vec3 cmiFateSpiral(vec3 a,vec3 b,vec3 coefficient,vec3 basis,vec3 reference,float t,float distance,float time,uint lane){
    vec3 tangent=cmiFateTangent(a,b,coefficient,t,reference);
    vec3 u=cmiFateTransport(basis,reference,tangent),v=cross(tangent,u);
    float theta=distance*1.570796327+time*.4+float(lane)*1.570796327;
    float taper=pow(max(0.0,sin(t*3.14159265)),.65);
    float radius=cmiFateRibbonRadius(length(b-a))*taper*(.96+.04*sin(distance*.8-time*.6));
    return cmiFateCurve(a,b,coefficient,t)+(u*cos(theta)+v*sin(theta))*radius;
}
float cmiFateRelease(float fraction,uint lane,uint seed){
    // Broad, stable groups dissolve at different times; no particle-sized flicker.
    float group=floor(clamp(fraction,0.0,.99999)*24.0);
    return .9*cmiFateRandom(seed^uint(group)*0x9e3779b9u^((lane+1u)*0x85ebca6bu));
}
vec3 cmiFateBlue(float distance,float time,uint lane,float shade){
    float pulse=cmiFatePulse(distance,time),breath=sin(distance*.7-time*.85+float(lane)*1.570796327);
    // Match the Anima tooltip: #44bcf9 body, #7ad6fb flow highlights.
    vec3 base=vec3(68,188,249)/255.0,highlight=vec3(122,214,251)/255.0;
    vec3 color=mix(base*.65,base,clamp(shade+.06*breath,0.0,1.0));
    return mix(color,highlight,pulse*.7);
}
