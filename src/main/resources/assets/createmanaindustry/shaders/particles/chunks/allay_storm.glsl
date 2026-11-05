#pragma cmi_include chunks/starfall.glsl
// ==== CMI shared allay-storm math (pose-free) ====
// Storm-shared layer split out of chunks/allay_pose.glsl so compute passes
// that need ONLY the wave/typhoon math (keygen's carrier sweep, update's
// steering, emit's analytic spawn, wavecontact's contact test) can include
// this chunk without pulling in the AllayModel pose port. Consumed by:
//   - keygen.comp        (carrier sweep: cmiStormWaveClaim)
//   - update.comp        (servo target + wave engagement)
//   - emit.comp          (analytic spawn)
//   - wavecontact.comp   (membership test)
//   - chunks/allay_pose.glsl (top-includes this chunk for cmiHash1)
// Injected verbatim by ParticlePrograms' #pragma cmi_include mechanism
// (recursively, depth-bound 4) — chunks/allay_pose.glsl is the ONLY nested
// include; every other consumer includes exactly one chunk.
//
// The wave uniforms + clock are declared HERE (next to the only functions
// that read them), which dedupes the three per-file declarations these
// replaced; cmiStormWaveClaim is THE carrier/sword predicate — keygen.comp
// (carrier partition) and model.vsh / the shader-pack merged source (tier
// pick) call the SAME function, so a member enters the carrier partition
// exactly when its sword renders. The go-stagger (cmiStormWaveGo) gates
// STEERING only, never the sword — matching the predicate this chunk
// replaces in model.vsh.
//
// Hash discipline (learned the hard way, see the identity chain in
// emit.comp): every hash input is built from NAMED single-mul statements —
// never an inline a*b+c — so driver FMA contraction cannot fork per-member
// constants between GPU vendors. The server's Java re-derivation
// (AllayStormWaves) validates contact reports against the SAME chains.

// Shared simulation uniforms. Consumers that already declared these must NOT
// re-declare them (duplicate declarations are a compile error under the
// text-splice include). Unused in a consumer is fine — the compiler strips
// them and the engine simply never sets those locations.
uniform vec4  uWave[4];      // x waveSeed, y fraction, z assembleSec, w diveUntilSec
uniform vec4  uWaveTarget[4]; // xyz live target aim point (feet + 0.5), w waveId (0 = inactive slot)

#pragma cmi_include chunks/storm_home.glsl

// ---- storm wave squads (server-launched dive waves) ------------------------
// Membership + launch-stagger rolls. SAME hash discipline as emit.comp's
// identity chain: NAMED single-mul statements only — an inline a*b+c may
// contract into an FMA and fork the selection between GPU vendors and the
// server's Java re-derivation (AllayStormWaves, which validates contact
// reports against the SAME chains). waveSeed is a 24-bit integer-valued
// float (exact); mseed is the member's identity seed in [0,1) from p3.z.
float cmiStormWaveRoll(float mseed, float waveSeed) {
    float s0 = cmiHash1(waveSeed + 13.37);
    float shift = s0 * 89.0;
    float base = mseed * 47.29;
    float q = base + shift;
    return cmiHash1(q);
}

bool cmiStormWaveMember(float mseed, float waveSeed, float fraction) {
    return cmiStormWaveRoll(mseed, waveSeed) < fraction;
}

// Personal launch-stagger roll in [0,1): staggers the squad's pursuit starts
// across the window (a swarm trickling in, not one lump). GPU-only — the
// server never phases individual members, it only brackets the window.
float cmiStormWaveGo(float mseed, float waveSeed) {
    float s0 = cmiHash1(waveSeed + 57.11);
    float shift = s0 * 57.0;
    float base = mseed * 13.77;
    float q = base + shift;
    return cmiHash1(q);
}

// THE carrier/sword predicate: the first live wave slot whose hash claims
// this member, or -1. Consumed by keygen.comp (which instance enters the
// held-item carrier partition) and by model.vsh / the shader-pack merged
// source (which tier renders) — one function, so the two decisions cannot
// diverge. Corpse and storm-gating stay at the call sites (keygen tests
// p3.y and the header storm slot itself; the render paths gate identically).
int cmiStormWaveClaim(float mseed) {
    for (int k = 0; k < 4; k++) {
        if (uWaveTarget[k].w < 0.5)
            continue; // slot inactive (waveId 0)
        if (uTimeSec < uWave[k].z || uTimeSec > uWave[k].w)
            continue; // outside the wave window
        if (!cmiStormWaveMember(mseed, uWave[k].x, uWave[k].y))
            continue;
        return k;
    }
    return -1;
}

// ---- dive-wave carried item (held sword) ------------------------------------
// The carried-item ramp of a wave-squad member under predicate (a): a hash
// member carries the sword for the WHOLE wave window, ramping the vanilla
// 5-tick arm raise in from the wave's assemble moment and back out so it
// reaches zero exactly at the deadline (C0 at both edges — a member never
// visibly snaps; aborts drop the uniforms, which is the rare hard cut).
// Pure function of the wave schedule — no state, identical on every client.
float cmiStormCarryRamp(float timeSec, float assembleSec, float diveUntilSec) {
    float up = clamp((timeSec - assembleSec) / 0.25, 0.0, 1.0);
    float down = clamp((diveUntilSec - timeSec) / 0.25, 0.0, 1.0);
    return min(up, down);
}

int cmiStormWaveClaim(float seed,float identity){return uStarfallEnabled?cmiStarClaim(identity,uTimeSec):cmiStormWaveClaim(seed);}
