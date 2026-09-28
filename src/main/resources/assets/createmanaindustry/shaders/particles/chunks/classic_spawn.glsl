#pragma cmi_types spawn
// Requires emitters, writeBuf, hash1 and randomDir. Caller reserves a valid slot.
void cmiSpawnClassic(uint eid, float seed, vec3 origin, float lightPacked, uint slot) {
    vec3 pos;
    vec3 velDir;
    // ---- classic style-0 path (shape-based) ----
    uint hb = eid * VEC4_PER_EMITTER;
    float shape = emitters.u[hb + 1u].x;
    float speedMin = emitters.u[hb + 1u].y;
    float speedMax = emitters.u[hb + 1u].z;
    float size = emitters.u[hb + 0u].w;
    float lifeMin = emitters.u[hb + 5u].x;
    float lifeMax = emitters.u[hb + 5u].y;
    float tanHalf = emitters.u[hb + 6u].y;

    float speed = mix(speedMin, speedMax, hash1(seed + 11.31));
    if (shape < 0.5) {                     // POINT
        pos = origin;
        velDir = randomDir(seed);
    } else if (shape < 1.5) {              // BOX (cube of half-extent `size`)
        vec3 uvw = vec3(hash1(seed + 1.0), hash1(seed + 2.0), hash1(seed + 3.0));
        pos = origin + (uvw - 0.5) * 2.0 * size;
        velDir = randomDir(seed + 4.0);
    } else if (shape < 2.5) {              // SPHERE (radius `size`)
        vec3 d = randomDir(seed + 5.0);
        pos = origin + d * (size * pow(hash1(seed + 6.0), 1.0 / 3.0));
        velDir = d;
    } else if (shape < 3.5) {              // CONE (height `size`, axis = windDir)
        vec3 axis = normalize(emitters.u[hb + 4u].xyz);
        // Build a tangent frame; guard the axis || +Y case BEFORE normalizing
        // (normalize of a zero cross product is NaN and would poison the spawn).
        vec3 side = cross(axis, vec3(0.0, 1.0, 0.0));
        if (dot(side, side) < 1e-6)
            side = cross(axis, vec3(1.0, 0.0, 0.0));
        vec3 t1 = normalize(side);
        vec3 t2 = cross(axis, t1);
        float hgt = size * hash1(seed + 7.0);
        float ang = 6.2831853 * hash1(seed + 8.0);
        float rad = tanHalf * hgt * sqrt(hash1(seed + 9.0));
        pos = origin + axis * hgt + (t1 * cos(ang) + t2 * sin(ang)) * rad;
        float spread = mix(0.0, tanHalf, hash1(seed + 10.0));
        vec3 sdir = normalize(axis + (t1 * cos(ang) + t2 * sin(ang)) * spread);
        velDir = sdir;
    } else {                               // PLANE (square, normal = header 19.xyz)
        vec3 normal = normalize(emitters.u[hb + 19u].xyz);
        // Build a stable basis on the face. The fallback handles horizontal
        // faces where the first cross product is degenerate.
        vec3 side = cross(normal, vec3(0.0, 1.0, 0.0));
        if (dot(side, side) < 1e-6)
            side = cross(normal, vec3(1.0, 0.0, 0.0));
        side = normalize(side);
        vec3 up = cross(normal, side);
        vec2 uv = vec2(hash1(seed + 1.0), hash1(seed + 2.0));
        pos = origin + side * ((uv.x - 0.5) * 2.0 * size)
                + up * ((uv.y - 0.5) * 2.0 * size);
        // A uniform random direction makes the Hex Spray wheel sample uniform
        // while gravity and wind take over the trajectory in update.comp.
        velDir = randomDir(seed + 4.0);
    }

    vec3 vel = velDir * speed;
#ifdef CMI_BLOCK_EMISSION
    // The registry validates additive-only sources. Constant-fold model/HP
    // branches out of the block kernel without duplicating the spawn math.
    const bool isModel = false;
#else
    float spawnMatId = emitters.u[hb + 7u].x;
    bool isModel = spawnMatId >= 1.5 && spawnMatId < 2.5;
#endif
    // MODEL particles are vanilla allays: the maxLife slot carries HIT POINTS
    // (Allay.createAttributes MAX_HEALTH = 20.0) instead of a lifetime -- they
    // are immortal and die only when the HP reaches zero. Every other material
    // keeps the header lifetime; the protective floor stays (a zero/garbage
    // lifetime header must never kill a particle the frame it spawns).
    float maxLife = isModel
            ? 20.0
            : max(mix(lifeMin, lifeMax, hash1(seed + 12.1)), 0.1);
    // Per-particle size. MODEL particles stand in for a vanilla ENTITY --
    // vanilla allays are uniform, so they render at the exact spec scale
    // (material check mirrors keygen). The only classic-path size variance
    // left is vanilla CherryParticle's 50/50 quadSize pick of 0.05 / 0.075 --
    // a spec base of 0.075 x {1.0, 2/3} -- keyed on flutter > 0 (the cherry
    // spec is the only flutter user); every other classic preset renders at
    // the exact spec scale (the old +/-30% jitter is gone with the cherry
    // alignment).
    float baseSize = 1.0;
    if (!isModel && emitters.u[hb + 7u].z > 0.0 && hash1(seed + 13.1) < 0.5)
        baseSize = 2.0 / 3.0;
    float billboardRoll = 0.0;
    // colorMode 6 is the glowing-vine wheel. Match Hex Spray's classic
    // ConjureParticle size variance and fixed random billboard roll while
    // leaving its plane spawn and requested dynamics untouched.
    if (emitters.u[hb + 17u].x > 5.5) {
        baseSize = mix(2.0 / 3.0, 4.0 / 3.0, hash1(seed + 13.1));
        billboardRoll = hash1(seed + 74.1) * 6.2831853;
    }
    // MODEL: p2.w is the per-particle HURT-FLASH timer (0 = not hurt), decayed
    // by update.comp; the model path never reads intensity, and the additive
    // path never sees MODEL particles. Textured non-MODEL: p2.w carries the
    // spawn-time packed light when the spec opts into the lightmap (vanilla
    // cherry leaves; header 17.z, the material-split slot -- the CPU samples
    // it per emit command into lightPacked, word c.x) or 1.0 fullbright
    // (vanilla particles spawn at alpha 1).
#ifdef CMI_BLOCK_EMISSION
    const float spawnLight = 1.0;
#else
    float spawnLight = isModel ? 0.0
            : (emitters.u[hb + 17u].z > 0.5 ? max(lightPacked, 0.0) : 1.0);
#endif


    if (isModel) cmiNewIdentity(slot);
    uint wb = slot * 4u;
    writeBuf.data[wb + 0u] = vec4(pos, baseSize);
    writeBuf.data[wb + 1u] = vec4(vel, billboardRoll);
    writeBuf.data[wb + 2u] = vec4(1.0, 1.0, 1.0, spawnLight);
    writeBuf.data[wb + 3u] = vec4(0.0, maxLife, seed, uintBitsToFloat(eid));
    cmiType_spawn(uint(emitters.u[hb].x), hb, writeBuf.data[wb], writeBuf.data[wb+1u],
                  writeBuf.data[wb+2u], writeBuf.data[wb+3u]);
}
