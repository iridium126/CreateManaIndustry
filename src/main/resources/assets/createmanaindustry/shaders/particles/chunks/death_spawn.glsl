void spawnDeathPoof(uint hb, vec4 p0, vec4 p1, vec4 p3) {
    float chain = emitters.u[hb + 16u].x; // deathEmitId (0 = none)
    if (chain < 0.5)
        return;
    // storm members carry their IDENTITY in p0.w (memberIdx+1, up to 131073)
    // -- the size multiplier for them is a constant 1.0 (header storm slot
    // 18.x > 0.5), or the poof metrics would scale with the member index
    float pmul = emitters.u[hb + 18u].x > 0.5 ? 1.0 : p0.w;
    float ssize = emitters.u[hb + 5u].w * pmul; // corpse life clamps to 1 → sizeEnd
    float sscale = (2.0 * ssize) / MODEL_ABOVE_FEET;
    float sw = 0.35 * sscale; // vanilla Allay bbWidth × render scale
    float sh = 0.6 * sscale;  // vanilla Allay bbHeight × render scale
    float light = p1.w;       // packed light stashed by the damage queue
    for (int i = 0; i < 20; i++) {
        // stride 7.31 ≈ 0.75 hash folds/step — adequate decorrelation for the
        // storm's [0,1) mseed, and for the WIDE non-storm seeds too (the old
        // ·91.7 pre-scale pushed the input to ULP 8 where the stride itself
        // rounded away, clumping the burst)
        float s = p3.z + float(i) * 7.31;
        vec2 ga = cmiGauss2(s + 21.0);
        vec2 gb = cmiGauss2(s + 41.0);
        vec3 pos = vec3(p0.x + (cmiHash1(s) * 2.0 - 1.0) * sw,
                        p0.y + cmiHash1(s + 1.0) * sh,
                        p0.z + (cmiHash1(s + 2.0) * 2.0 - 1.0) * sw);
        vec3 vel = vec3(ga.x * 0.4 + (cmiHash1(s + 3.0) * 2.0 - 1.0),
                        ga.y * 0.4 + (cmiHash1(s + 4.0) * 2.0 - 1.0),
                        gb.x * 0.4 + (cmiHash1(s + 5.0) * 2.0 - 1.0));
        // Lifetime = the vanilla ExplodeParticle distribution 16/(0.8r+0.2)+2
        // ticks, continuous (no integer-tick quantization — the engine
        // integrates continuous ages). Range (0.9, 4.1] s, biased short like
        // vanilla; the POOF header's life min/max are range documentation only.
        float maxLife = max((16.0 / (0.8 * cmiHash1(s + 12.1) + 0.2) + 2.0) / 20.0, 0.1);
        uint slot = atomicAdd(counter.writeSlot, 1u);
        if (slot >= uCapacity)
            return;
        uint wb = slot * 4u;
        // p0.w: exact vanilla ExplodeParticle quadSize 0.1*(r1*r2*6+1) — the
        // product of two INDEPENDENT uniforms (mean 0.25, pdf -ln-shaped, not
        // a single r²) — via the 0.30 spec size × this ((r1*r2*6+1)/3)
        // multiplier. Range [0.1, 0.7] half-extent; the distribution shape is
        // biased low exactly like vanilla.
        writeBuf.data[wb + 0u] = vec4(pos,
                (cmiHash1(s + 13.1) * cmiHash1(s + 13.7) * 6.0 + 1.0) / 3.0);
        writeBuf.data[wb + 1u] = vec4(vel, 0.0);
        writeBuf.data[wb + 2u] = vec4(1.0, 1.0, 1.0, light);
        writeBuf.data[wb + 3u] = vec4(0.0, maxLife, s, uintBitsToFloat(uint(chain)));
    }
}

