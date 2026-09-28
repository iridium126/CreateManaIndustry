void cmiStormWavePursuit(uint slot, vec3 pos, vec3 liveTarget, float cap,
        out vec3 target, out vec3 vel) {
    uint b = slot * 6u;
    // pass 1: nearest arc-length position on the polyline
    float best = 1e30;
    float bestS = 0.0;
    float acc = 0.0;
    float total = 0.0;
    vec3 prev = uWavePath[b].xyz;
    for (int i = 1; i < 6; i++) {
        vec4 pw = uWavePath[b + uint(i)];
        if (pw.w < 0.5)
            break; // valid waypoints form a prefix
        vec3 ab = pw.xyz - prev;
        float len2 = max(dot(ab, ab), 1e-8);
        float t = clamp(dot(pos - prev, ab) / len2, 0.0, 1.0);
        vec3 c = prev + ab * t;
        float d2 = dot(pos - c, pos - c);
        float len = sqrt(len2);
        if (d2 < best) {
            best = d2;
            bestS = acc + t * len;
        }
        acc += len;
        prev = pw.xyz;
    }
    total = acc;
    // pass 2: advance LOOKAHEAD along the polyline
    float s = bestS + STORM_WAVE_LOOKAHEAD;
    target = liveTarget;
    if (s < total) {
        acc = 0.0;
        prev = uWavePath[b].xyz;
        for (int i = 1; i < 6; i++) {
            vec4 pw = uWavePath[b + uint(i)];
            if (pw.w < 0.5)
                break;
            vec3 ab = pw.xyz - prev;
            float len = length(ab);
            if (s <= acc + len) {
                target = prev + ab * ((s - acc) / max(len, 1e-6));
                break;
            }
            acc += len;
            prev = pw.xyz;
        }
    }
    vec3 toT = target - pos;
    float dist = length(toT);
    vel = dist > 1e-4 ? toT * (min(cap, dist * 4.0) / dist) : vec3(0.0);
}

// Whisker-cone obstacle avoidance for engaged divers: 13 short rays (forward
// + 35/70/90-degree rings on both tangent axes) against the containing bake
// slice, scored by free depth then direction agreement. Bends the steering
// direction around player-placed obstacles instead of stalling: the plain
// collision sweep only answers EMBEDDED particles, so a member pressed
// against a freshly placed roof used to hover there forever with the servo
// pinning it. All-blocked returns the direction unchanged — genuine embeds
// fall through to the escape scan.
vec3 cmiStormWhisker(vec3 pos, vec3 dir) {
    int slice = -1;
    vec3 mOrigin = vec3(0.0);
    for (int i = 0; i < BAKE_SLICES; i++) {
        vec4 meta = bakeMeta.m[i];
        if (meta.w < 0.5)
            continue;
        vec3 rel = pos - meta.xyz;
        ivec3 tc = ivec3(floor(rel + vec3(0.0001)));
        if (tc.x >= 0 && tc.x < SX && tc.y >= 0 && tc.y < SY && tc.z >= 0 && tc.z < SZ) {
            slice = i;
            mOrigin = meta.xyz;
            break;
        }
    }
    if (slice < 0)
        return dir; // no collision data here (open-sky column above the shaft)
    if (escapeScan(pos, mOrigin, slice, dir, 2) > 0.0)
        return dir; // ahead clear within 2 voxels: no avoidance needed
    vec3 up = abs(dir.y) > 0.9 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0);
    vec3 ta = normalize(cross(dir, up));
    vec3 tb = normalize(cross(dir, ta));
    vec3 bestDir = dir;
    float bestScore = 0.0;
    for (int r = 0; r < 12; r++) {
        float c = (r < 4) ? 0.819 : ((r < 8) ? 0.342 : 0.0);
        float s = (r < 4) ? 0.574 : ((r < 8) ? 0.940 : 1.0);
        int sub = r & 3;
        vec3 side = (sub < 2) ? ta : tb;
        float sgn = ((sub & 1) == 0) ? 1.0 : -1.0;
        vec3 rd = dir * c + side * (s * sgn);
        float free = escapeScan(pos, mOrigin, slice, rd, 3);
        if (free <= 0.0)
            continue;
        float score = free + 0.25 * dot(rd, dir);
        if (score > bestScore) {
            bestScore = score;
            bestDir = rd;
        }
    }
    return normalize(bestDir);
}

// Death chain: a MODEL particle whose corpse countdown just crossed the end of
// the vanilla 20-tick death window (LivingEntity.tickDeath broadcasting event
// 60) spawns its poof burst HERE -- the corpse position is GPU-only state, so
// the CPU can neither time nor place this. 20 particles with the vanilla
// makePoofParticles distribution: position uniform inside the allay bounding
// box (getRandomX/Z(1.0) = ±bbWidth, getRandomY = 0..bbHeight), velocity =
// gaussian*0.02 + uniform*±0.05 blocks/tick = gaussian*0.4 + uniform*±1.0 b/s.
// Lifetime = the vanilla ExplodeParticle distribution (computed below, not the
// header's range fields); drag/gravity/sprites ride the poof emitter's header;
// lighting comes from the light stash the damage queue left in p1.w.
