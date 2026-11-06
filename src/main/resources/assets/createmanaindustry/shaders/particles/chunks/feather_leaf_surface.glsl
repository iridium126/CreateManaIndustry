#ifndef CMI_FEATHER_LEAF_SURFACE
#define CMI_FEATHER_LEAF_SURFACE
ivec3 cmiFeatherSupportBlock(vec3 position) {
    return ivec3(floor(position - vec3(0.,.02,0.) + .0001));
}
float cmiFeatherCross(vec2 a, vec2 b) { return a.x*b.y - a.y*b.x; }

float cmiFeatherDiagonal(vec2 position, vec2 a, vec2 b, vec2 c) {
    return cmiFeatherCross(c-a,position-a) * sign(cmiFeatherCross(c-a,b-a));
}

vec3 cmiFeatherAttach(vec3 position, vec3 a, vec3 b, vec3 c,
                      vec3 da, vec3 db, vec3 dc, vec3 lift, out vec3 normal) {
    vec2 ab = b.xz-a.xz, ac = c.xz-a.xz, ap = position.xz-a.xz;
    float area = cmiFeatherCross(ab,ac);
    float wb = cmiFeatherCross(ap,ac)/area, wc = cmiFeatherCross(ab,ap)/area;
    normal = normalize(cross(db-da,dc-da));
    if (normal.y < 0.) normal = -normal;
    return da + wb*(db-da) + wc*(dc-da) + (position.y-a.y)*lift;
}
#endif
