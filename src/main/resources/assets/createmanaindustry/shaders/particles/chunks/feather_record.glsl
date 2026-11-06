#ifndef CMI_FEATHER_RECORD
#define CMI_FEATHER_RECORD
#pragma cmi_include chunks/feather_quad.glsl
// Feather-only record union: negative size = resting; p2 = cached motion;
// p3.z = frame[0:3], lift cooldown ticks[4:14], newly landed[15].
float cmiFeatherHash(float p){p=fract(p*.1031);p*=p+33.33;p*=p+p;return fract(p);}
float cmiFeatherRoll(vec4 motion,vec4 cached,float age){return motion.w+(cached.z+cached.w*(age+.05))*age;}
uint cmiFeatherFrame(vec4 age){return floatBitsToUint(age.z)&15u;}
uint cmiFeatherPalette(vec4 age){return (floatBitsToUint(age.z)>>16u)&3u;}
uint cmiFeatherPaletteRoll(float seed){
    uint h=floatBitsToUint(seed)^0x9e3779b9u;
    h^=h>>16u;h*=0x7feb352du;h^=h>>15u;h*=0x846ca68bu;h^=h>>16u;
    return h%100u;
}
void cmiFeatherInit(inout vec4 p0,inout vec4 p1,inout vec4 p2,inout vec4 p3){
    float seed=p3.z,angle=radians(cmiFeatherHash(seed)*60.);
    p0.w=cmiFeatherHash(seed+13.1)<.5?2./3.:1.;p1=vec4(0);
    p2=vec4(cos(angle)*2.,sin(angle)*2.,radians(cmiFeatherHash(seed*1.31)>.5?30.:-30.),radians(cmiFeatherHash(seed*2.47)>.5?2.5:-2.5));
    uint palette=cmiFeatherPaletteRoll(seed);
    p3.xy=vec2(0,60);p3.z=uintBitsToFloat(min(11u,uint(cmiFeatherHash(seed*7.13)*12.))|((palette<60u?0u:palette<92u?1u:2u)<<16u));
}
void cmiFeatherPoint(vec4 p0,vec4 p1,vec4 p2,vec4 p3,vec3 right,vec3 up,vec2 q,
        out vec3 position,out vec3 normal,out vec2 uv){
    float roll=p0.w<0.?p1.w:cmiFeatherRoll(p1,p2,p3.x);
    vec2 r=cmiFeatherQuadOffset(p0.w,roll,q);
    position=p0.xyz+(p0.w<0.?vec3(r.x,0,r.y):right*r.x+up*r.y);
    normal=p0.w<0.?vec3(0,1,0):normalize(cross(right,up));
    vec4 rect=CMI_FEATHER_RECTS[cmiFeatherFrame(p3)+12u*cmiFeatherPalette(p3)];
    uv=rect.xy+vec2(q.x,1.-q.y)*rect.zw;
}
void cmiFeatherVertex(vec4 p0,vec4 p1,vec4 p2,vec4 p3,vec3 right,vec3 up,int vertex,
        out vec3 position,out vec3 normal,out vec2 uv){
    cmiFeatherPoint(p0,p1,p2,p3,right,up,cmiFeatherQuadPoint(vertex),position,normal,uv);
}
#endif
