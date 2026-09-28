// Included in update.comp; shares its declared simulation state.
    // ---- storm swarm steering ------------------------------------------------
    // Typhoon: world-frame SERVO onto each member's circulating home
    // point on one of 6 trailing log-spiral arms (shared cmiTyphoonHome:
    // eyewall ascent -> outflow deck -> edge sink -> inflow, rigid co-rotation,
    // funnel-shaped outflow deck). The center is the SERVER-CHASED
    // anchor (header 19) — no per-member wander. Equilibrium == the analytic
    // spawn state in emit.comp (the SAME shared function), so late joiners
    // start settled and the pattern phase is identical on every client.
    // Omega and the phases are CLIENT-DERIVED, not wire state: the spin rate
    // is SPIN_K / R_now (the fringe tangential speed holds at 0.85 * the
    // member speed cap at every size), and the rotation/conveyor PHASES are
    // CPU-side growth-law integrals — never rate * timeSec, whose
    // radius-dependent coefficient would teleport the pattern (and collapse
    // the whole storm onto the center) whenever the radius moves.
    // Only WORLD-frame acceleration leaves this block: the collision sweep
    // below still owns the final position move.
    if (!corpse && stormA.x > 0.5) {
        // ----- typhoon: world-frame servo onto the circulating arm home point
        vec3 anchor = emitters.u[hb + 19u].xyz;
        float spMaxV = max(stormA.w, 0.5);
        // Shared home-point function (chunks/allay_pose.glsl) — the SAME
        // function emit.comp spawns with, so the two call sites cannot
        // diverge and the spawn state IS this servo's equilibrium. The
        // rotation/conveyor phases arrive as CPU-side growth-law integrals
        // (AllayStormRuntime) — never rate * timeSec, which would teleport
        // the pattern whenever the growing radius moves.
        vec3 target;
        vec3 vT;
        cmiTyphoonHome(p3.z, max(stormA.y, 2.0), uStormOmegaNow, uStormRotPhase,
                uStormConvPhase, uStormConvRate, spMaxV, anchor, target, vT);

        // ---- wave squad override (server brain, event-driven) --------------
        // First matching slot claims the member (deterministic dedupe across
        // clients and against the server's Java re-derivation). Engagement is
        // STATELESS: the per-member roll staggers the launch inside the wave
        // window, and the single pass ends on contact (position-derived) or
        // the window deadline — after which the member simply falls back to
        // its typhoon home point. One approach per member, never re-engaging.
        bool waveEngaged = false;
        // One approach per member per wave. The stateless contact test is
        // REVERSIBLE — a member that turned home re-crosses the contact
        // radius within a frame or two and the hash test re-engages it,
        // ping-ponging between "pursue" and "home" on the shell until the
        // window ends (the reported in-place jitter). The FIRST contact
        // frame therefore latches MEMBER_LATCH_BIT into p0.w (survives pool
        // compaction; cleared only by a new storm generation killing the
        // pool), and a latched member never engages again.
        if ((uint(p0.w + 0.5) & MEMBER_LATCH_BIT) == 0u) {
        for (uint k = 0u; k < 4u && !waveEngaged; k++) {
            vec4 wt = uWaveTarget[k];
            if (wt.w < 0.5)
                continue; // slot inactive (waveId 0)
            vec4 w = uWave[k];
            if (uTimeSec < w.z || uTimeSec > w.w)
                continue; // outside the wave window
            if (!cmiStormWaveMember(p3.z, w.x, w.y))
                continue;
            float go = w.z + cmiStormWaveGo(p3.z, w.x)
                    * min(STORM_WAVE_STAGGER, (w.w - w.z) * 0.6);
            if (uTimeSec < go)
                continue; // not this member's turn yet
            if (distance(pos, wt.xyz) < STORM_WAVE_CONTACT_R) {
                waveContactLatched = true;
                break; // contacted: latch the pass as done, stay home
            }
            waveEngaged = true;
            cmiStormWavePursuit(k, pos, wt.xyz, spMaxV, target, vT);
        }
        }

        // separation + player repulsion, world space.
        // Same visit budget as the boids sweep had.
        vec3 sepW = vec3(0.0);
        int sepVisits = 0;
        bool sepBudgetGone = false;
        ivec3 pc = ivec3(floor(pos / CELL_SIZE));
        for (int ox = -1; ox <= 1 && !sepBudgetGone; ox++)
        for (int oy = -1; oy <= 1 && !sepBudgetGone; oy++)
        for (int oz = -1; oz <= 1 && !sepBudgetGone; oz++) {
            ivec3 c3 = pc + ivec3(ox, oy, oz);
            uint hcell = (uint(c3.x) * 92837111u ^ uint(c3.y) * 689287499u ^ uint(c3.z) * 283923481u)
                    & (GRID_TABLE - 1u);
            int sN = grid.heads[int(hcell)];
            while (sN != 0) {
                int j = sN - 1;
                sN = grid.next[j];
                if (++sepVisits > STORM_MAX_VISITS) {
                    sepBudgetGone = true;
                    break;
                }
                if (j == int(idx))
                    continue;
                vec3 op = readBuf.data[uint(j) * 4u].xyz;
                vec3 d = pos - op;
                float d2 = dot(d, d);
                if (d2 < STORM_SEP_R * STORM_SEP_R && d2 > 1e-8) {
                    float dl = sqrt(d2);
                    sepW += d * ((STORM_SEP_R - dl) / (dl * STORM_SEP_R));
                }
            }
        }
        sepW *= STORM_W_SEP;
        vec3 repW = vec3(0.0);
        // every synced player repels (all-client-consistent force set).
        // Squared-distance gate: the sqrt and the normalize
        // divides run only for members actually inside player reach.
        // Engaged divers SKIP the repulsion entirely — they must reach their
        // target (separation above still keeps the swarm off itself).
        if (!waveEngaged) {
            for (int pi = 0; pi < uPlayerCount; pi++) {
                vec3 dp = pos - players.pl[pi].xyz;
                float dp2 = dot(dp, dp);
                if (dp2 < STORM_PLAYER_R * STORM_PLAYER_R && dp2 > 1e-6) {
                    float dpL = sqrt(dp2);
                    repW += (dp / dpL) * STORM_PLAYER_W * (STORM_PLAYER_R - dpL) / STORM_PLAYER_R;
                }
            }
        }
        // whisker cone bends the engaged steering around obstacles (player
        // roofs/walls) instead of stalling against them
        if (waveEngaged && uCollisionOn == 1) {
            vec3 toT = target - pos;
            float dT = length(toT);
            if (dT > 1e-4) {
                vec3 adj = cmiStormWhisker(pos, toT / dT);
                target = pos + adj * dT;
                vT = adj * min(spMaxV, dT * 4.0);
            }
        }

        // servo onto the co-rotating home point, plus the world-space terms,
        // hand ONE acceleration to the integrator
        vec3 aW = (target - pos) * STORM_VORTEX_K + (vT - vel) * STORM_VORTEX_C + sepW + repW;
        vel += aW * uDt;
        float spdV = length(vel);
        if (spdV > spMaxV)
            vel *= spMaxV / spdV;
    }

    // ---- network correction (storm members, server-synced positions) -------
    // SOFT convergence only: an acceleration toward the extrapolated target
    // plus a velocity blend. Positions always travel through the integrator
    // (velocity × time, speed-capped by the storm clamp above), so a stale or
    // multi-block correction reads as "flying back into formation", never a
    // teleport. Regular snapshots ramp their strength with slot age -- early
    // window lets local physics speak, late window converges before the next
    // snapshot lands; hit corrections (server DAMAGE broadcasts) run a fixed
    // short window at high strength so the corpse/poof plays at the exact
    // spot the attacker struck.
    // HEADER storm slot gate (see the damage-queue isStorm note): member 0's
    // p0.w (1.0) is indistinguishable from a non-storm MODEL multiplier
    if (!corpse && stormA.x > 0.5) {
        uint memberIdx = (uint(p0.w + 0.5) & MEMBER_IDX_MASK) - 1u;
        // correction slots are sized by the POOL capacity (2 vec4 per member);
        // an identity beyond it can never have a slot, so skip instead of
        // addressing outside the buffer
        if (memberIdx < uCapacity) {
            uint ci = memberIdx * 2u;
            vec4 cA = correction.c[ci];
            vec4 cB = correction.c[ci + 1u];
            float cage = uTimeSec - cA.w;
            if (cB.w > 0.5 && cage >= 0.0) {
                bool hitC = cB.w > 2.5;
                float window = hitC ? 0.25 : 1.5;
                if (cage <= window) {
                    vec3 target = cA.xyz + cB.xyz * cage; // velocity extrapolation
                    float ramp = hitC ? 1.0 : (0.25 + 0.75 * clamp(cage / window, 0.0, 1.0));
                    float k = (hitC ? 24.0 : 8.0) * ramp;
                    vel += (target - pos) * min(k * uDt, 0.5);
                    vel += (cB.xyz - vel) * min(2.5 * uDt, 0.35);
                }
            }
        }
    }

