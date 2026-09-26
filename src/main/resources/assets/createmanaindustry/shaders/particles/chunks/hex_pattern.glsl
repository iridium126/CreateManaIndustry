#pragma cmi_include chunks/hex_pattern_state.glsl
layout(std430, binding = 26) readonly buffer HexResources { vec4 v[]; } hexResource;
#pragma cmi_include chunks/hex_color.glsl
vec3 hexMorph(uint offset, uint count, float time, vec3 position) {
    precise float inputPhase = time + float(dot(dvec3(0.1lf), dvec3(position)));
    float phase = mod(mod(inputPhase, 1.0) + 1.0, 1.0);
    float f = phase * float(count);
    uint i = uint(floor(f)) % count;
    return trunc(mix(hexResource.v[offset + i].rgb,
        hexResource.v[offset + (i + 1u) % count].rgb, hexCubic(fract(f))));
}
vec3 hexMinimumWheel(float time, vec3 position) {
    const vec3 wheel[6] = vec3[6](vec3(32,0,0),vec3(32,32,0),vec3(0,32,0),vec3(0,32,32),vec3(0,0,32),vec3(32,0,32));
    float seconds = float(double(time) / 20.0lf);
    precise float inputPhase = float(double(seconds) / 20.0lf) + float(dot(dvec3(0.1lf), dvec3(position)));
    float phase = mod(mod(inputPhase, 1.0) + 1.0, 1.0) * 6.0;
    uint i = uint(floor(phase)) % 6u;
    return trunc(mix(wheel[i], wheel[(i + 1u) % 6u], hexCubic(fract(phase))));
}
vec4 hexPatternColor(uint slot) {
    uint b = hexBase(slot);
    vec4 pigment = hexInput.v[b + 3u];
    vec3 rgb;
    if (pigment.z < 0.0) {
        uint argb = floatBitsToUint(hexInput.v[b + 2u].w);
        rgb = vec3((argb >> 16) & 255u, (argb >> 8) & 255u, argb & 255u);
    } else {
        uint offset = floatBitsToUint(pigment.x), count = floatBitsToUint(pigment.y);
        vec3 position = hexInput.v[b + 2u].xyz;
        rgb = pigment.z == 0.0 ? hexResource.v[offset].rgb
            : hexMorph(offset, count, float(double(hexInput.v[0].z) / double(pigment.z)), position);
        double luminance = dot(dvec3(rgb), dvec3(0.2126lf, 0.7152lf, 0.0722lf)) / 255.0;
        if (luminance < 0.05) rgb += hexMinimumWheel(hexInput.v[0].z, position);
    }
    float ticks = hexInput.v[b + 1u].x;
    float alpha = ticks <= 60.0 ? floor(ticks / 60.0 * 255.0) / 255.0 : 1.0;
    return vec4(rgb / 255.0, alpha);
}
