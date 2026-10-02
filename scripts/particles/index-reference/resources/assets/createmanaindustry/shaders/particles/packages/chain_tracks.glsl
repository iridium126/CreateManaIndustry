struct ChainTrack { vec4 startRadius; vec4 endLength; vec4 motion; uvec4 nodes; };
struct ChainEvent { uvec4 identity; uvec4 envelope; uvec4 masks; vec4 progress; };
float chainAngleDelta(float a,float b) { return mod(b-a+180.0,360.0)-180.0; }
bool chainLoopCrossed(float before,float after,float threshold,bool reversed) {
    float a=sign(chainAngleDelta(threshold,before)),b=sign(chainAngleDelta(threshold,after));
    return reversed?a>b:a<b;
}
