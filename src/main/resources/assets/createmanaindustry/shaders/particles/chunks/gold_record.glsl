#ifndef CMI_GOLD_RECORD
#define CMI_GOLD_RECORD
uint cmiGoldHash(uint h){h^=h>>16u;h*=0x7feb352du;h^=h>>15u;h*=0x846ca68bu;return h^(h>>16u);}
float cmiGoldUnit(uint h){return float(cmiGoldHash(h)>>8u)*(1./16777216.);}
void cmiGoldInit(inout vec4 p0,inout vec4 p1,inout vec4 p2,inout vec4 p3){
    uint seed=floatBitsToUint(p3.z);float angle=cmiGoldUnit(seed+1u)*6.2831853;
    bool star=cmiGoldUnit(seed+2u)<.2;
    p0.w=star?mix(.08,.16,cmiGoldUnit(seed+3u)):mix(.025,.06,cmiGoldUnit(seed+3u));
    float radius=mix(.08,.22,cmiGoldUnit(seed+4u));
    p2=vec4(vec2(cos(angle),sin(angle))*radius,mix(.5,1.1,cmiGoldUnit(seed+5u)),mix(.5,1.1,cmiGoldUnit(seed+6u))*(cmiGoldUnit(seed+7u)<.5?-1.:1.));
    p1=vec4(-p2.y*p2.w,p2.z,p2.x*p2.w,angle);
    p3.xyz=vec3(0,mix(8.,12.,cmiGoldUnit(seed+8u)),.8+.2*cos(angle));
}
#endif
