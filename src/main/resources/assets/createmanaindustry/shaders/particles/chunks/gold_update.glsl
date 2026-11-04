// Ordinary update owns lifetime/compaction. Fifth-order small-angle rotation
// avoids per-frame hashes and trigonometry; |omega*dt| <= .275 in production.
void cmiGoldUpdate(vec4 p0,vec4 p1,vec4 p2,vec4 p3){
    if(p3.x>=p3.y)return;
    float a=p2.w*uDt,a2=a*a,c=1.-a2*.5+a2*a2/24.,s=a*(1.-a2/6.+a2*a2/120.);
    vec2 next=vec2(c*p2.x-s*p2.y,s*p2.x+c*p2.y);
    vec3 delta=vec3(next.x-p2.x,p2.z*uDt,next.y-p2.y);
    p0.xyz+=delta;p1.xyz=delta/max(uDt,.000001);p1.w+=p2.w*uDt*.2;p2.xy=next;
    p3.x+=uDt;p3.z=.8+.2*next.x*inversesqrt(max(dot(next,next),.000001));
    uint slot=cmiReserveParticle();if(slot>=uCapacity)return;cmiHistoryContinue(slot);uint b=slot*4u;
    writeBuf.data[b]=p0;writeBuf.data[b+1u]=p1;writeBuf.data[b+2u]=p2;writeBuf.data[b+3u]=p3;
}
