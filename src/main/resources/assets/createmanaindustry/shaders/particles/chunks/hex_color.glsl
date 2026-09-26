// Shared with conjure's frozen color wheel. Byte quantization is applied only
// by the live pigment evaluator, not by the existing sampled conjure wheel.
float hexCubic(float t) {
    return t < 0.5 ? 4.0 * t * t * t : 1.0 - pow(-2.0 * t + 2.0, 3.0) / 2.0;
}
vec3 hexScreenColor(vec3 c) {
    return floor((floor(c * 255.0 + 0.5) + 255.0) / 2.0) / 255.0;
}
