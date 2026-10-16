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
float cmiFateGlow(float time,float born,bool animate){return .45*mix(.2,1.0,animate?smoothstep(0.0,.6,time-born):1.0);}

// Rotation-minimizing frames retain their orientation when the endpoint chord approaches vertical.
vec3 cmiFateDirection(vec3 delta,vec3 fallback){return dot(delta,delta)>1e-10?normalize(delta):fallback;}
vec3 cmiFateBasis(vec3 tangent){
    vec3 axis=abs(tangent.y)<.8?vec3(0,1,0):vec3(1,0,0);
    return normalize(cross(tangent,axis));
}
vec3 cmiFateTransport(vec3 normal,vec3 from,vec3 to){
    vec3 axis=cross(from,to);float cosine=clamp(dot(from,to),-1.0,1.0);
    vec3 moved=cosine<-.9999?normal:normal+cross(axis,normal)+cross(axis,cross(axis,normal))/max(1e-5,1.0+cosine);
    moved-=to*dot(moved,to);
    return dot(moved,moved)>1e-10?normalize(moved):cmiFateBasis(to);
}
vec3 cmiFateDerivative(vec3 a,vec3 b,vec3 coefficient,float t){
    vec3 derivative=b-a;if(coefficient.x<.001)return derivative;
    float scale=coefficient.y,h=coefficient.z,u=coefficient.x/(2.0*scale),z=2.0*t-1.0;
    float sineDerivative;
    if(abs(u)<.05){
        float u2=u*u,z2=z*z;
        sineDerivative=(3.0*z2-1.0)*u*u2/3.0+(5.0*z2*z2-1.0)*u*u2*u2/60.0;
    }else sineDerivative=2.0*u*cosh(z*u)-2.0*sinh(u);
    derivative.y+=scale*(sqrt(1.0+h*h)*2.0*u*sinh(z*u)+h*sineDerivative);
    return derivative;
}
vec3 cmiFateTangent(vec3 a,vec3 b,vec3 coefficient,float t,vec3 fallback){
    return cmiFateDirection(cmiFateDerivative(a,b,coefficient,t),fallback);
}
float cmiFateRibbonRadius(float span){return .05*smoothstep(0.0,1.0,span);}
