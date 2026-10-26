#ifndef CMI_ALLAY_SKY
#define CMI_ALLAY_SKY
// Linear radiance, in the same world direction basis for the dimension and its window.
vec3 cmiAllaySky(vec3 direction, vec4 context) {
    vec3 d = normalize(direction);
    float day = 0.5 + 0.5 * cos(context.x * 6.28318530718);
    float horizon = pow(1.0 - abs(d.y), 3.0);
    vec3 color = mix(vec3(0.006, 0.013, 0.030), vec3(0.018, 0.060, 0.073), horizon);
    color *= mix(0.72, 1.0, day) * (1.0 - 0.22 * context.y - 0.15 * context.z);
    // Sparse, fixed direction markers make orientation continuity inspectable.
    vec3 north = normalize(vec3(0.0, 0.32, 1.0));
    vec3 east = normalize(vec3(1.0, 0.51, 0.0));
    vec3 south = normalize(vec3(0.0, 0.68, -1.0));
    vec3 west = normalize(vec3(-1.0, 0.22, 0.0));
    float width = max(0.000018, fwidth(dot(d, north)));
    float marks = smoothstep(0.99994-width, 0.99994+width, dot(d, north));
    marks += 0.8*smoothstep(0.99996-width, 0.99996+width, dot(d, east));
    marks += 0.6*smoothstep(0.99997-width, 0.99997+width, dot(d, south));
    marks += 0.4*smoothstep(0.99997-width, 0.99997+width, dot(d, west));
    vec3 cell = floor(d * 380.0);
    float star = fract(sin(dot(cell, vec3(127.1, 311.7, 74.7)) + context.w) * 43758.5453);
    vec3 center = normalize(cell + 0.5);
    float tiny = smoothstep(0.9999995 - width*0.08, 0.9999995 + width*0.08, dot(d, center));
    color += vec3(0.20, 0.33, 0.36) * (marks + tiny * step(0.9992, star));
    return color;
}
float cmiRiftEnvelope(vec2 offset, float age) {
    float radius = length(offset);
    if (radius < 0.0001) return 1.0;
    float ellipseRadius = radius / length(offset / vec2(96.0, 80.0));
    float boundary = mix(64.0, ellipseRadius, smoothstep(0.0, 2.0, age));
    return 1.0 - smoothstep(boundary - 0.4, boundary, radius);
}
// Returns a positive main-camera distance only. A horizontal ray never becomes a horizon stripe.
float cmiRiftDistance(vec3 worldDirection, vec4 cameraRelativeRift) {
    if (cameraRelativeRift.w < 0.0 || abs(worldDirection.y) < 0.000001) return -1.0;
    float distanceToPlane = cameraRelativeRift.y / worldDirection.y;
    if (distanceToPlane <= 0.0) return -1.0;
    vec2 offset = worldDirection.xz * distanceToPlane - cameraRelativeRift.xz;
    return cmiRiftEnvelope(offset, cameraRelativeRift.w) > 0.0 ? distanceToPlane : -1.0;
}
#endif
