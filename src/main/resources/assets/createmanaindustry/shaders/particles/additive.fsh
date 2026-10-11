// Additive particle fragment shader: soft radial falloff + distance fade.
// colorMode-4/5 (Hexcasting pigment/direct conjure) particles carry flat
// vHex = 1 and switch
// to a procedural replica of hexcasting's cloud.png: a pointy-top hexagonal
// soft glow (pure-white RGB, the shape lives entirely in the alpha channel —
// 32×32, hexagon spanning texels 1..30, ~2.5-texel soft edge).
uniform float uFadeDist;
uniform float uGlow;

in vec2 vUv;
in vec3 vColor;
in float vAlpha;
in float vDist;
flat in float vHex;

out vec4 fragColor;

// Hexagonal soft glow over the quad UV (0..1). Pointy-top orientation
// (vertices up/down, flat left/right sides — matching cloud.png), centered
// hexagon of circumradius ≈ 0.453 UV (apothem 0.866·R ≈ 0.392), interior
// plateau 1.0, soft edge ≈ 2.5/32 UV via a smoothstep on the hex SDF.
#pragma cmi_include chunks/hex_shape.glsl

void main() {
    float alpha;
    if (vHex > 0.5) {
        alpha = vAlpha * hexCloudShape(vUv);
    } else {
        vec2 c = vUv - 0.5;
        float r2 = dot(c, c);
        if (r2 > 0.25)
            discard;
        float radial = smoothstep(0.25, 0.0, r2);
        alpha = vAlpha * radial * radial;
    }
    float farFade = 1.0 - smoothstep(uFadeDist, uFadeDist + 24.0, vDist);
    alpha *= farFade;
    vec3 col = vColor * (alpha * uGlow);
    // alpha 0.0: with (ONE, ONE) alpha blending this leaves the destination's
    // alpha channel untouched instead of accumulating 1 per overlapping quad.
    fragColor = vec4(col, 0.0);
}
