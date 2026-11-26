float manaThinLine(float distance, float width, float aa) {
    return 1.0 - smoothstep(width, width + aa, abs(distance));
}

vec4 cmiShadeManaBeam() {
    if (vMana.x < -0.001) discard;
    vec2 q = vUv * 2.0 - 1.0;
    float r2 = dot(q, q);
    float radius = sqrt(r2);
    float feather = 1.0 - smoothstep(0.73, 1.0, abs(q.y));
    vec3 color = vColor;
    float profile;
    if (vHex < 5.5) {
        float width = mix(0.12, 1.0, smoothstep(0.05, 4.5, vMana.y))
                * (0.94 + 0.06 * sin(vMana.y * 1.6 - vMana.z * 19.0));
        float x = abs(q.y) / width;
        float x2 = x * x;
        float pulse = 0.86 + 0.14 * sin(vMana.y * 1.65 - vMana.z * 28.0);
        float nearHead = exp(-0.70 * max(0.0, vMana.w - vMana.y));
        vec3 halo = vec3(0.38, 0.055, 0.92) * (0.34 * exp(-4.2 * x2));
        vec3 inner = vec3(0.59, 0.21, 1.0) * (0.53 * exp(-25.0 * x2));
        vec3 spine = vec3(0.95, 0.80, 1.0) * (0.93 * exp(-105.0 * x2));
        color = (halo + inner + spine) * (pulse + nearHead * 0.19);
        profile = (1.0 - smoothstep(0.73, 1.0, x)) * smoothstep(0.02, 0.88, vMana.y);
    } else if (vHex < 6.5) {
        float x2 = q.y * q.y;
        float surge = 0.31 + 0.69 * pow(0.5 + 0.5 * sin(vMana.y * 2.0 - vMana.z * 19.0), 3.0);
        profile = feather * (0.24 * exp(-3.8 * x2) + 0.71 * exp(-34.0 * x2)) * surge;
        profile *= smoothstep(0.04, 0.9, vMana.y);
    } else if (vHex < 7.5) {
        float aa = clamp(fwidth(radius), 0.004, 0.08);
        float theta = atan(q.y, q.x + 0.000001);
        float outer = manaThinLine(radius - 0.86, 0.012, aa);
        float rings = 0.65 * manaThinLine(radius - 0.68, 0.009, aa)
                + 0.40 * manaThinLine(radius - 0.35, 0.008, aa);
        float ticks = pow(0.5 + 0.5 * cos(18.0 * theta), 15.0)
                * smoothstep(0.71, 0.76, radius) * (1.0 - smoothstep(0.81, 0.84, radius));
        float triangle = radius * cos(mod(theta + 1.047198, 2.094395) - 1.047198);
        float inverse = radius * cos(mod(theta, 2.094395) - 1.047198);
        float sigil = max(manaThinLine(triangle - 0.30, 0.008, aa),
                          manaThinLine(inverse - 0.30, 0.008, aa));
        profile = (0.92 * outer + rings + 0.80 * ticks + 0.69 * sigil + 0.17 * exp(-10.0 * abs(radius - 0.79)))
                * (1.0 - smoothstep(0.92, 1.0, radius));
    } else if (vHex < 8.5) {
        color = mix(vColor, vec3(1.0, 0.88, 1.0), exp(-12.5 * r2));
        profile = (0.48 * exp(-5.0 * r2) + 0.89 * exp(-23.0 * r2))
                * (1.0 - smoothstep(0.76, 1.0, radius));
    } else {
        vec2 edge = pow(max(vec2(0), 1.0 - abs(q)), vec2(3.0));
        float crossLight = max(exp(-68.0 * q.x * q.x) * edge.y, exp(-68.0 * q.y * q.y) * edge.x);
        profile = (crossLight + 0.35 * exp(-6.8 * r2)) * (1.0 - smoothstep(0.72, 1.0, radius));
    }
    float fade = (1.0 - smoothstep(uFadeDist, uFadeDist + 24.0, vDist)) * smoothstep(0.19, 1.28, vDist);
    return vec4(color * (vAlpha * profile * fade * uGlow), 0.0);
}
