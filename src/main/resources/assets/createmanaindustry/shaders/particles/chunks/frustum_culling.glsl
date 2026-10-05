// Camera-relative conservative tests shared by particle and package compute passes.
bool cmiOutsideRange(vec3 rel,float rangeSquared) {
    return rangeSquared>=0.0 && dot(rel,rel)>rangeSquared;
}
bool cmiOutsideSphere(vec4 plane,vec3 rel,float radius) {
    return dot(plane.xyz,rel)+plane.w < -radius;
}
bool cmiOutsideSweep(vec4 plane,vec3 a,vec3 b,float radius) {
    return max(dot(plane.xyz,a),dot(plane.xyz,b))+plane.w < -radius;
}
bool cmiOutsideBox(vec4 plane,vec3 lo,vec3 hi) {
    vec3 support=vec3(plane.x<0.0?lo.x:hi.x,plane.y<0.0?lo.y:hi.y,plane.z<0.0?lo.z:hi.z);
    return dot(plane.xyz,support)+plane.w<0.0;
}
