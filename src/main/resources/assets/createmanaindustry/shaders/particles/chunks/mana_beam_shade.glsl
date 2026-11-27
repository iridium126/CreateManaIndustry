float manaThinLine(float distance, float width, float aa) {
    return 1.0 - smoothstep(width, width + aa, abs(distance));
}

// ---- Casting-circle glyph band -------------------------------------------
// The 18 procedural ticks are replaced by the mana beam's own Hexcasting
// sequence, repeated twice (14 glyphs). Glyphs are authored in Hexcasting hex
// units (y down) and rotated so their +y points at the circle centre.
// Every glyph is evaluated in its own 1/14 sector, so a fragment only pays for
// one glyph (<= 9 segments) instead of all 37.
const float MANA_GLYPH_SECTOR = 6.2831853 / 14.0;
const float MANA_GLYPH_RING = 0.778;
const float MANA_GLYPH_UNIT = 0.052;

// Rose casting circle: rose outer ring/glow, deep-rose inner ring,
// rose runes and a champagne-gold hexagram core.
const vec3 MANA_CIRCLE_INNER = vec3(0.92, 0.16, 0.36);
const vec3 MANA_CIRCLE_GLYPH = vec3(1.00, 0.44, 0.62);
const vec3 MANA_CIRCLE_SIGIL = vec3(1.00, 0.80, 0.62);

float manaSeg(vec2 p, vec2 a, vec2 b) {
    vec2 pa = p - a;
    vec2 ba = b - a;
    float h = clamp(dot(pa, ba) / max(dot(ba, ba), 1e-6), 0.0, 1.0);
    return length(pa - ba * h);
}

#define MANA_SEG(d, p, a, b) d = min(d, manaSeg(p, a, b))

// hexcasting:get_caster "qaq" -- diamond.
float manaGlyphReflect(vec2 p) {
    float d = 1e9;
    MANA_SEG(d, p, vec2(0.0, 1.5), vec2(0.8660254, 0.0));
    MANA_SEG(d, p, vec2(0.8660254, 0.0), vec2(0.0, -1.5));
    MANA_SEG(d, p, vec2(0.0, -1.5), vec2(-0.8660254, 0.0));
    MANA_SEG(d, p, vec2(-0.8660254, 0.0), vec2(0.0, 1.5));
    return d;
}

// hexcasting:entity_pos/eye "aa" -- triangle.
float manaGlyphEye(vec2 p) {
    float d = 1e9;
    MANA_SEG(d, p, vec2(-0.8660254, 0.75), vec2(0.8660254, 0.75));
    MANA_SEG(d, p, vec2(0.8660254, 0.75), vec2(0.0, -0.75));
    MANA_SEG(d, p, vec2(0.0, -0.75), vec2(-0.8660254, 0.75));
    return d;
}

// hexcasting:get_entity_look "wa" -- heading bar with a 120 degree kink.
float manaGlyphLook(vec2 p) {
    float d = 1e9;
    MANA_SEG(d, p, vec2(-1.7320508, 0.75), vec2(0.0, 0.75));
    MANA_SEG(d, p, vec2(0.0, 0.75), vec2(1.7320508, 0.75));
    MANA_SEG(d, p, vec2(1.7320508, 0.75), vec2(0.8660254, -0.75));
    return d;
}

// hexcasting:raycast "wqaawdd" -- the widest glyph in the sequence.
float manaGlyphRay(vec2 p) {
    float d = 1e9;
    MANA_SEG(d, p, vec2(-2.1650635, 0.0), vec2(-0.4330127, 0.0));
    MANA_SEG(d, p, vec2(-0.4330127, 0.0), vec2(1.2990381, 0.0));
    MANA_SEG(d, p, vec2(1.2990381, 0.0), vec2(2.1650635, -1.5));
    MANA_SEG(d, p, vec2(2.1650635, -1.5), vec2(0.4330127, -1.5));
    MANA_SEG(d, p, vec2(0.4330127, -1.5), vec2(1.2990381, 0.0));
    MANA_SEG(d, p, vec2(1.2990381, 0.0), vec2(2.1650635, 1.5));
    MANA_SEG(d, p, vec2(2.1650635, 1.5), vec2(0.4330127, 1.5));
    MANA_SEG(d, p, vec2(0.4330127, 1.5), vec2(1.2990381, 0.0));
    return d;
}

// hexcasting:number "aqaaq" -- the value 5 used by the beam's explosion.
float manaGlyphNumber(vec2 p) {
    float d = 1e9;
    MANA_SEG(d, p, vec2(-1.2990381, 0.0), vec2(-0.4330127, 1.5));
    MANA_SEG(d, p, vec2(-0.4330127, 1.5), vec2(0.4330127, 0.0));
    MANA_SEG(d, p, vec2(0.4330127, 0.0), vec2(-0.4330127, -1.5));
    MANA_SEG(d, p, vec2(-0.4330127, -1.5), vec2(-1.2990381, 0.0));
    MANA_SEG(d, p, vec2(-1.2990381, 0.0), vec2(0.4330127, 0.0));
    MANA_SEG(d, p, vec2(0.4330127, 0.0), vec2(1.2990381, -1.5));
    return d;
}

// hexcasting:explode "aawaawaa" -- six-point burst closing on the origin.
float manaGlyphExplode(vec2 p) {
    float d = 1e9;
    MANA_SEG(d, p, vec2(0.0, 0.0), vec2(1.7320508, 0.0));
    MANA_SEG(d, p, vec2(1.7320508, 0.0), vec2(0.8660254, -1.5));
    MANA_SEG(d, p, vec2(0.8660254, -1.5), vec2(0.0, 0.0));
    MANA_SEG(d, p, vec2(0.0, 0.0), vec2(-0.8660254, 1.5));
    MANA_SEG(d, p, vec2(-0.8660254, 1.5), vec2(0.8660254, 1.5));
    MANA_SEG(d, p, vec2(0.8660254, 1.5), vec2(0.0, 0.0));
    MANA_SEG(d, p, vec2(0.0, 0.0), vec2(-0.8660254, -1.5));
    MANA_SEG(d, p, vec2(-0.8660254, -1.5), vec2(-1.7320508, 0.0));
    MANA_SEG(d, p, vec2(-1.7320508, 0.0), vec2(0.0, 0.0));
    return d;
}

float manaGlyphDistance(vec2 p, int glyph) {
    if (glyph == 1) return manaGlyphEye(p);
    if (glyph == 3) return manaGlyphLook(p);
    if (glyph == 4) return manaGlyphRay(p);
    if (glyph == 5) return manaGlyphNumber(p);
    if (glyph == 6) return manaGlyphExplode(p);
    return manaGlyphReflect(p);
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
        float outer = manaThinLine(radius - 0.875, 0.012, aa);
        float rings = 0.65 * manaThinLine(radius - 0.68, 0.009, aa);

        // 14 glyphs = the seven-pattern sequence twice, clockwise, every
        // 360/14 degrees. Only the glyph nearest this fragment's angle is
        // evaluated, and only inside the band + tangential guard.
        float glyph = 0.0;
        if (aa < 0.040 && radius > 0.695 && radius < 0.860) {
            float rel = (theta - 1.5707963) / MANA_GLYPH_SECTOR;
            float base = floor(rel + 0.5);
            float phi = (rel - base) * MANA_GLYPH_SECTOR;
            float gx = radius * sin(phi);
            if (abs(gx) < 0.135) {
                float gy = MANA_GLYPH_RING - radius; // +y points at the centre
                float gd = manaGlyphDistance(vec2(gx, gy) / MANA_GLYPH_UNIT,
                        int(mod(-base, 7.0))) * MANA_GLYPH_UNIT;
                glyph = (0.25 * manaThinLine(gd, 0.012, aa)
                        + 0.85 * manaThinLine(gd, 0.006, aa))
                        * (1.0 - smoothstep(0.018, 0.036, aa));
            }
        }

        float triangle = radius * cos(mod(theta + 1.047198, 2.094395) - 1.047198);
        float inverse = radius * cos(mod(theta, 2.094395) - 1.047198);
        float sigil = max(manaThinLine(triangle - 0.30, 0.008, aa),
                          manaThinLine(inverse - 0.30, 0.008, aa));
        vec3 lit = color * (0.92 * outer + 0.17 * exp(-10.0 * abs(radius - 0.79)))
                + MANA_CIRCLE_INNER * rings
                + MANA_CIRCLE_GLYPH * (0.95 * glyph)
                + MANA_CIRCLE_SIGIL * (0.69 * sigil);
        color = lit;
        profile = 1.0 - smoothstep(0.92, 1.0, radius);
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
