// Exact native palette parameters live in this frame's command tail, never in a permanent emitter header.
uniform uint uHexParameterBase;
double hexCube(double x){return x*x*x;}
float hexModulo(float value){precise float rem=value-trunc(value);precise float shifted=rem+1.;return shifted-trunc(shifted);}
uint hexParameters(uint command){return uHexParameterBase+uint(emitCmd.entries[command*12u+7u])*48u;}
vec3 hexRgb(uint argb){return vec3((argb>>16u)&255u,(argb>>8u)&255u,argb&255u);}
vec3 hexMorph(uint parameters,vec3 direction){
    uint count=uint(emitCmd.entries[parameters+1u]);
    if(count==1u)return hexRgb(floatBitsToUint(emitCmd.entries[parameters+8u]));
    // Java casts the double dot product to float before adding its float time.
    precise float projection=float(.1lf*double(direction.x)+.1lf*double(direction.y)+.1lf*double(direction.z));
    precise float phase=emitCmd.entries[parameters+2u]+projection;
    precise float wrapped=hexModulo(phase);
    precise float index=wrapped*float(count);
    uint base=uint(floor(index));precise float t=index-float(base);
    precise float weight=t<.5?4.*t*t*t:float(1.lf-hexCube(double(-2.*t+2.))/2.lf);
    vec3 a=hexRgb(floatBitsToUint(emitCmd.entries[parameters+8u+base%count]));
    vec3 b=hexRgb(floatBitsToUint(emitCmd.entries[parameters+8u+(base+1u)%count]));
    precise vec3 result=a+weight*(b-a);
    return trunc(result);
}
vec3 hexMinimumLuminance(vec3 color,float time,vec3 direction){
    double brightness=(.2126lf*double(color.r)+.7152lf*double(color.g)+.0722lf*double(color.b))/255.lf;
    if(brightness>=.05lf)return color/255.;
    const uint wheel[6]=uint[6](0xff200000u,0xff202000u,0xff002000u,0xff002020u,0xff000020u,0xff200020u);
    precise float projection=float(.1lf*double(direction.x)+.1lf*double(direction.y)+.1lf*double(direction.z));
    precise float phase=(time/20.)/20.+projection;
    precise float index=hexModulo(phase)*6.;
    uint base=uint(floor(index));precise float t=index-float(base);
    precise float weight=t<.5?4.*t*t*t:float(1.lf-hexCube(double(-2.*t+2.))/2.lf);
    vec3 a=hexRgb(wheel[base%6u]),b=hexRgb(wheel[(base+1u)%6u]);
    precise vec3 correction=a+weight*(b-a);
    return (color+trunc(correction))/255.;
}
vec3 hexPigment(uint command,vec3 direction){
    uint p=hexParameters(command);
    if(emitCmd.entries[p]>.5)return hexMinimumLuminance(hexMorph(p,direction),emitCmd.entries[p+3u],direction);
    // Sampled-wheel compatibility API. Native packets always use explicit palettes.
    float n=length(direction);float phase=dot(n>1e-5?direction/n:vec3(0,1,0),normalize(vec3(.3,.8,.5)))*.5+.5;
    float x=fract(phase)*8.;uint a=uint(floor(x))%8u,b=(a+1u)%8u;float t=fract(x);
    t=t<.5?4.*t*t*t:1.-pow(-2.*t+2.,3.)*.5;
    vec3 ca=vec3(emitCmd.entries[p+8u+a*4u],emitCmd.entries[p+9u+a*4u],emitCmd.entries[p+10u+a*4u]);
    vec3 cb=vec3(emitCmd.entries[p+8u+b*4u],emitCmd.entries[p+9u+b*4u],emitCmd.entries[p+10u+b*4u]);
    return mix(ca,cb,t);
}
