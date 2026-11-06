#ifndef CMI_FEATHER_QUAD
#define CMI_FEATHER_QUAD
vec2 cmiFeatherQuadPoint(int vertex) {
    return vec2((vertex==1||vertex==4||vertex==5)?1.:0.,vertex>=2&&vertex!=4?1.:0.);
}
vec2 cmiFeatherQuadOffset(float size, float roll, vec2 q) {
    float c = cos(roll), s = sin(roll);
    vec2 a = q - .5;
    return vec2(c*a.x-s*a.y,s*a.x+c*a.y) * (.15 * abs(size));
}
#endif
