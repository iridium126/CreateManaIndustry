// Packed angles match the renderer's Minecraft yaw convention. No simulation dependencies.
const float CMI_ANIM_PI=3.141592654,CMI_ANIM_TAU=6.28318530718;
float cmiAnimationAngle(float from,float to){return mod(to-from+CMI_ANIM_PI,CMI_ANIM_TAU)-CMI_ANIM_PI;}
uint cmiAnimationPack(vec2 values){uvec2 v=uvec2(round(clamp(values,0.,1.)*65535.));return v.x|(v.y<<16u);}
vec2 cmiAnimationUnpack(uint bits){return vec2(float(bits&65535u),float(bits>>16u))/65535.;}
uint cmiAnimationAngles(float yaw,float pitch){return cmiAnimationPack(vec2(mod(yaw+CMI_ANIM_PI,CMI_ANIM_TAU)/CMI_ANIM_TAU,pitch/CMI_ANIM_PI+.5));}
vec2 cmiAnimationAngles(uint bits){vec2 a=cmiAnimationUnpack(bits);return vec2(a.x*CMI_ANIM_TAU-CMI_ANIM_PI,(a.y-.5)*CMI_ANIM_PI);}
uint cmiAnimationHead(float yaw,float amount){return cmiAnimationPack(vec2(mod(yaw+CMI_ANIM_PI,CMI_ANIM_TAU)/CMI_ANIM_TAU,amount));}
vec2 cmiAnimationHead(uint bits){vec2 a=cmiAnimationUnpack(bits);return vec2(a.x*CMI_ANIM_TAU-CMI_ANIM_PI,a.y);}
// Exact sum of n vanilla WalkAnimationState updates for a held displacement sample.
vec2 cmiAnimationWalk(float speed,float target,float ticks){float decay=pow(.6,ticks);return vec2(target+(speed-target)*decay,ticks*target+(speed-target)*1.5*(1.-decay));}
float cmiAnimationTurn(float from,float to,float maximum){return from+clamp(cmiAnimationAngle(from,to),-maximum,maximum);}
// Counter after n ticks; spin rises during cycle ticks 1..14 then decays at 15..28.
float cmiAnimationSpin(float tick,bool cycled){float t=mod(tick,55.);return t<15.?t+(cycled?1.:0.):max(0.,(cycled?29.:28.)-t);}
