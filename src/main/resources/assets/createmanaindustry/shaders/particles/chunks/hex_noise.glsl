// Minecraft's seeded three-dimensional SimplexNoise. Double coordinates keep
// the player's integer hash seed intact even at large values.
int hexPerm(int i) { return int(hexResource.v[uint(i & 255)].x); }
double hexCorner(int hash, dvec3 p) {
    const ivec3 gradients[12] = ivec3[12](ivec3(1,1,0),ivec3(-1,1,0),ivec3(1,-1,0),ivec3(-1,-1,0),
        ivec3(1,0,1),ivec3(-1,0,1),ivec3(1,0,-1),ivec3(-1,0,-1),
        ivec3(0,1,1),ivec3(0,-1,1),ivec3(0,1,-1),ivec3(0,-1,-1));
    double t = 0.6lf - dot(p, p);
    if (t < 0.0lf) return 0.0lf;
    t *= t;
    return t * t * dot(dvec3(gradients[hash % 12]), p);
}
double hexNoise(dvec3 p) {
    p *= 0.6lf;
    ivec3 ijk = ivec3(floor(p + (p.x + p.y + p.z) / 3.0lf));
    dvec3 a = p - (dvec3(ijk) - double(ijk.x + ijk.y + ijk.z) / 6.0lf);
    ivec3 i1, i2;
    if (a.x >= a.y) {
        if (a.y >= a.z) { i1 = ivec3(1,0,0); i2 = ivec3(1,1,0); }
        else if (a.x >= a.z) { i1 = ivec3(1,0,0); i2 = ivec3(1,0,1); }
        else { i1 = ivec3(0,0,1); i2 = ivec3(1,0,1); }
    } else {
        if (a.y < a.z) { i1 = ivec3(0,0,1); i2 = ivec3(0,1,1); }
        else if (a.x < a.z) { i1 = ivec3(0,1,0); i2 = ivec3(0,1,1); }
        else { i1 = ivec3(0,1,0); i2 = ivec3(1,1,0); }
    }
    ivec3 h = ijk & 255;
    int g0 = hexPerm(h.x + hexPerm(h.y + hexPerm(h.z)));
    int g1 = hexPerm(h.x+i1.x + hexPerm(h.y+i1.y + hexPerm(h.z+i1.z)));
    int g2 = hexPerm(h.x+i2.x + hexPerm(h.y+i2.y + hexPerm(h.z+i2.z)));
    int g3 = hexPerm(h.x+1 + hexPerm(h.y+1 + hexPerm(h.z+1)));
    return 16.0lf * (hexCorner(g0,a) + hexCorner(g1,a-dvec3(i1)+1.0lf/6.0lf)
        + hexCorner(g2,a-dvec3(i2)+1.0lf/3.0lf) + hexCorner(g3,a-0.5lf));
}
