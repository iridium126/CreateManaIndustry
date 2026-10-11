float hexCloudShape(vec2 uv) {
    vec2 p = uv - 0.5;
    // IQ hexagon SDF, xy-swapped for the pointy-top orientation
    const vec3 kk = vec3(-0.866025404, 0.5, 0.577350269);
    vec2 q = abs(p.yx);
    q -= 2.0 * min(dot(kk.xy, q), 0.0) * kk.xy;
    float r = 0.392; // apothem
    q -= vec2(clamp(q.x, -kk.z * r, kk.z * r), r);
    float d = length(q) * sign(q.y);
    return 1.0 - smoothstep(-0.01, 0.078, d);
}

