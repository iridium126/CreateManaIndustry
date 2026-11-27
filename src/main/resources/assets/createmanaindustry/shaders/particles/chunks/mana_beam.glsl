// Shared timeline and four-vec4 record contract for an additive mana beam.
// p0: world anchor and conservative radius; p1: cast axis and total length.
// p2: anchor/arc start distance, variant or arc end, role, signed hit axis.
// p3: age, lifetime, cast seed, emitter id.
const float MANA_SPEED = 60.0;
const float MANA_HOLD = 0.30;
const float MANA_FADE = 0.55;
const float MANA_TAU = 6.2831853;

const uint MANA_CORE = 0u;
const uint MANA_HEAD = 1u;
const uint MANA_ORIGIN_RING = 2u;
const uint MANA_ARC = 4u;
const uint MANA_REVERSE_ARC = 5u;
const uint MANA_TRAIL = 6u;
const uint MANA_IMPACT = 7u;

float manaHeadDistance(float range, float age) {
    return min(range, age * MANA_SPEED);
}

float manaEnvelope(float range, float age) {
    return 1.0 - smoothstep(0.0, MANA_FADE, age - range / MANA_SPEED - MANA_HOLD);
}

vec3 manaHitNormal(float face) {
    float axis = abs(face);
    return vec3(axis == 1.0 ? 1.0 : 0.0, axis == 2.0 ? 1.0 : 0.0, axis == 3.0 ? 1.0 : 0.0) * sign(face);
}

float manaHitPlane(vec3 offset, vec3 axis, float station, float range, float face) {
    return face == 0.0 ? 1.0 : dot(axis * (station - range) + offset, manaHitNormal(face));
}

float manaRandom(float value) {
    float p = fract(value * 0.1031);
    p *= p + 33.33;
    return fract(p * (p + p));
}
