// Stable true catenary, expressed as a correction to the endpoint chord.
// cosh(x)-cosh(u) uses a product identity; small-u sinh differences use a series.
vec3 cmiFateCurve(vec3 a,vec3 b,vec3 coefficient,float t);
vec3 cmiFateCoefficients(vec3 a,vec3 b){
    float d=length(b.xz-a.xz);
    if(d<.001)return vec3(0.0);
    // Fade sag continuously into the vertical fallback rather than changing shape at a threshold.
    float sag=clamp(d*.04,.08,2.0)*smoothstep(.001,.08,d);
    if(sag<1e-5)return vec3(0.0);
    // Solve maximum vertical sag against the chord, including unequal heights.
    float low=d*.08,high=max(d*length(b-a)/(8.0*sag),low)*4.0;
    for(int step=0;step<16;step++){
        float candidate=(low+high)*.5,u=d/(2.0*candidate);
        float h=(b.y-a.y)/(2.0*candidate*sinh(u));
        float t=clamp(.5+.5*(asinh((b.y-a.y)/d)-asinh(h))/u,0.0,1.0);
        float dip=mix(a.y,b.y,t)-cmiFateCurve(a,b,vec3(d,candidate,h),t).y;
        if(dip>sag)low=candidate;else high=candidate;
    }
    float scale=high;
    float u=d/(2.0*scale),h=(b.y-a.y)/(2.0*scale*sinh(u));
    return vec3(d,scale,h);
}
vec3 cmiFateCurve(vec3 a,vec3 b,vec3 coefficient,float t){
    vec3 position=mix(a,b,t);float d=coefficient.x;
    if(d<.001||t<=0.0||t>=1.0)return position;
    float scale=coefficient.y,h=coefficient.z,u=d/(2.0*scale),z=2.0*t-1.0;
    float cosine=2.0*sinh(u*t)*sinh(u*(t-1.0)),sine;
    if(abs(u)<.05){
        float u2=u*u,z2=z*z;
        sine=z*(z2-1.0)*u*u2/6.0+z*(z2*z2-1.0)*u*u2*u2/120.0;
    }else sine=sinh(z*u)-z*sinh(u);
    position.y+=scale*(sqrt(1.0+h*h)*cosine+h*sine);
    return position;
}
float cmiFateHash(float value){return fract(sin(value*12.9898+78.233)*43758.5453);}
float cmiFateGlow(float time,float born,bool animate){return .45*mix(.2,1.0,animate?smoothstep(0.0,.35,time-born):1.0);}
