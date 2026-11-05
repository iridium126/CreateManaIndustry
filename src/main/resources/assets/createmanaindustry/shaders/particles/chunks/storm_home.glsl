#ifndef CMI_STORM_FN
#define CMI_STORM_FN
#define CMI_STORM_OUT(T) out T
#define CMI_STORM_SHADER 1
#endif

// NAMED single-mul statements only — see the discipline note above.
CMI_STORM_FN precise float cmiHash1(float p) {
    // fract(mul) otherwise contracts the subtraction into FMA on some drivers.
    precise float scaled=p*0.1031f;
    precise float fraction=scaled-floor(scaled);
    precise float factor=fraction+33.33f;
    precise float product=fraction*factor;
    precise float doubled=product+product;
    precise float cubic=product*doubled;
    precise float result=cubic-floor(cubic);
    return result;
}

CMI_STORM_FN precise float cmiStormMemberSeed(float stormSeed, uint memberIdx) {
    precise float s0 = cmiHash1(stormSeed + 31.7f);  // [0,1), one value per storm
    precise float shift = s0 * 97.0f;
    precise float t = float(memberIdx) * 0.6123f;
    precise float p = t + 0.7f;
    precise float q = p + shift;             // spans ~[0.7, 80360] -> ~8285 folds
    precise float hA = cmiHash1(q);
    precise float q2 = q * 7.31f;             // second pass, decorrelated input
    precise float hB = cmiHash1(q2);
    precise float mA = hA * 0.618f;
    precise float mB = hB * 0.382f;
    precise float mseed = mA + mB;           // [0,1); 2048 members: exact, 131072: ~95% distinct
    return mseed;
}


// ---- typhoon home-point ----------------------------------------------------
// Shared servo-target / analytic-spawn function for the typhoon shape rework.
// Consumed by update.comp (the servo target + its velocity) and emit.comp (the
// analytic spawn, whose state IS the servo's equilibrium — late joiners start
// settled). Pure function of (memberSeed, radius, omegaNow, rotPhase,
// convPhase, convRate, maxSpeed, center): synced params + the CPU-side
// growth-law phase integrals only, so every client derives the same targets by
// construction; local separation / repulsion / collision drift is healed by
// the soft correction layer. The center is the SERVER-CHASED anchor — there is
// no per-member wander on this path.
//
// PHASE DISCIPLINE (the clump-bug contract): the rotation and conveyor phases
// arrive as CPU-integrated scalars (rotPhase / convPhase / convRate — see
// AllayStormRuntime). The shader must NEVER multiply an accumulated clock
// (timeSec) by a radius-dependent rate: with a growing radius that product
// re-derives the whole pattern phase every frame, amplified by the world
// clock into target teleports and a full-storm collapse onto the center.
// The geometry below scales PURELY proportionally with the radius (no
// absolute floors) — that is what keeps omegaNow * reach == 0.85 * cap at
// every storm size and the phase integrals branch-free.
//
// Shape contract (design review):
//   - 6 trailing log-spiral arms, total winding pi across [rEye, reach],
//     chirality = -sign(omegaNow): the outer ends lag the spin, like real
//     rainbands. Each member's own reach tops out past R (uniform per-member
//     fringe of 0.15R) — density decays ~linearly beyond R and the
//     edge-turnaround dwell pile spreads into a soft rim.
//   - Members ride a closed SECONDARY-CIRCULATION loop per arm: eyewall ascent
//     -> outflow deck -> outer-edge sink -> low-level inflow -> repeat. r(s)
//     is a raised cosine (zero radial speed at BOTH turnarounds) and the aloft
//     fraction w(s) = 0.5 + 0.5*sin (peaks mid-ascent). The eyewall density
//     pile-up is the dwell time at the r turnaround — no explicit radial bias
//     needed (the outer edge gains a softer rim the same way).
//   - VERTICAL BAND FILL: the circulation line is only the column's CENTER —
//     every member adds a static uniform offset (v_i - 0.5) * H with
//     H = clamp(0.4R, 3, 8), so the ensemble fills the storm's volume instead
//     of riding the circulation surface (the old shell was hollow inside).
//   - Funnel: the outflow deck stands clamp(0.2R, 2, 6) above the flat deck at
//     the eyewall, tapering linearly to the outer edge.
//   - Static per-member micro jitter (<= 0.4 blocks) keeps the swarm organic.
CMI_STORM_FN void cmiTyphoonHome(float ms, float radiusR, float omegaNow, float rotPhase, float convPhase,
        float convRate, float maxSpeed, vec3 center, CMI_STORM_OUT(vec3) pos, CMI_STORM_OUT(vec3) vel) {
    // geometry scales PURELY proportionally with the radius (no absolute
    // floors — see the phase-discipline note above)
    float rEye = radiusR * 0.10f;

    // arm index (0..5) + gaussian angular jitter, sigma = 0.2 * arm spacing
    // (2pi/6 = 1.04719755; sigma = 0.2094 rad — arm gaps drop to ~4% of the
    // arm-core density)
    precise float hArm = ms * 5.7f;
    float armAngle = floor(cmiHash1(hArm) * 6.0f) * 1.04719755f;
    float hG = ms * 9.1f;
    float gu1 = max(cmiHash1(hG), 1e-6);
    float gu2 = cmiHash1(hG + 17.17f);
    float jitter = sqrt(-2.0f * log(gu1)) * cos(6.2831853f * gu2) * 0.2094f;

    // per-member conveyor rate (mean radial speed 0.15..0.35 b/s) + fringe
    float hRt = ms * 13.9f;
    float rate = 0.15f + cmiHash1(hRt) * 0.2f;
    float hFr = ms * 23.1f;
    float fringeH = cmiHash1(hFr);
    // span = reach - rEye = R * (0.9 + 0.15*fringeH) — the loop's radial extent
    float aI = 0.9f + 0.15f * fringeH;
    float span = radiusR * aI;
    float reach = radiusR * (1.0f + 0.15f * fringeH);
    // conveyor phase: the CPU integrates rate/(2*span(R(t))) over the frozen
    // growth law into the SHARED scalar convPhase; this member scales it by
    // rate/aI. d(convPhase_i)/dt = rate/(2*span) — the designed radial speed
    // at EVERY storm size, with no accumulated-clock term anywhere.
    float convP = convPhase * (rate / aI);
    float hPh = ms * 11.3f;
    float sc = 6.2831853f * fract(cmiHash1(hPh) + convP);

    // the (r, y) circulation loop: raised-cosine radius, sinusoidal aloft
    float cyc = (1.0f - cos(sc)) * 0.5f;      // 0 at the eyewall .. 1 at the edge
    float r = rEye + span * cyc;
    float aloft = 0.5f + 0.5f * sin(sc);
    // funnel height at r — (r - rEye) / (reach - rEye) IS cyc, no division needed
    float funnel = clamp(radiusR * 0.2f, 2.0f, 6.0f) * (1.0f - cyc);
    // vertical band fill: the circulation line is the column's CENTER, every
    // member adds a static uniform offset across the band half-width H — the
    // ensemble fills the volume (the old circulation-only shell was hollow)
    float hV = ms * 29.3f;
    float bandH = clamp(radiusR * 0.4f, 3.0f, 8.0f);
    float y = -1.2f + (funnel + 1.2f) * aloft + (cmiHash1(hV) - 0.5f) * bandH;

    // trailing spiral arm + rigid co-rotation (the pattern phase is the
    // CPU-side growth-law integral; the arm shape is scale-invariant, so
    // `spiral` is a pure member constant)
    float spiral = 3.14159265f / log(reach / rEye);
    float theta = armAngle + jitter + rotPhase
            - sign(omegaNow) * spiral * log(r / rEye);
    float ct = cos(theta);
    float st = sin(theta);

    // static organic micro jitter (<= 0.4 blocks, unit direction from hashes)
    float hJ = ms * 17.3f;
    float ja = cmiHash1(hJ) * 6.2831853f;
    float hJc = hJ + 3.1f;
    float jc = cmiHash1(hJc) * 2.0f - 1.0f;
    float jr = sqrt(max(0.0f, 1.0f - jc * jc));
    float hJm = ms * 19.3f;
    vec3 joff = vec3(cos(ja) * jr, jc, sin(ja) * jr) * (0.4f * cmiHash1(hJm));

    pos = center + vec3(ct * r, y, st * r) + joff;

    // target velocity: orbital + circulation slide (radial + vertical) + the
    // arm-parallel slide implied by theta(r) — capped at the member speed cap.
    // convRate = d(convPhase)/dt carries the current conveyor rate; the aI
    // factors cancel against span, leaving drdt = R*pi*sin*rate*convRate.
    float drdt = radiusR * 3.14159265f * sin(sc) * rate * convRate;
    float dydt = (funnel + 1.2f) * 3.14159265f * cos(sc) * (rate / aI) * convRate;
    float tang = omegaNow * r - sign(omegaNow) * spiral * drdt;
    vel = vec3(-st * tang + ct * drdt, dydt, ct * tang + st * drdt);
    float spd = length(vel);
    if (spd > maxSpeed)
        vel = vel * (maxSpeed / spd);
}

// Growth births start outside the visibility envelope. Activate-sourced
// members are already settled at their own analytic home.
CMI_STORM_FN void cmiStormBirth(uint member, bool ring, float radius, float cap,
        vec3 center, vec3 home, vec3 homeVelocity, CMI_STORM_OUT(vec3) position, CMI_STORM_OUT(vec3) velocity) {
    if (!ring) {
        position = home;
        velocity = homeVelocity;
        return;
    }
    float angle = float(member) * 2.399963f;
    float reach = 120.0f + radius;
    position = center + vec3(cos(angle) * reach, 0.0f, sin(angle) * reach);
    vec3 towardHome = home - position;
    float distanceHome = length(towardHome);
    velocity = distanceHome > 1e-3f ? towardHome * (cap / distanceHome) : vec3(0.0f);
}

#ifdef CMI_STORM_SHADER
#undef CMI_STORM_SHADER
#undef CMI_STORM_OUT
#undef CMI_STORM_FN
#endif
