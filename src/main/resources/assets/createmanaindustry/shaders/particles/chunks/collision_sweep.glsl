// Included in update.comp; shares its declared simulation state.
    // ---- block collision: axis-separated sweep from the PRE-move position ----
    // Each axis tests one step ahead; a blocked axis zeroes its velocity and
    // keeps the particle in place, a free axis advances exactly vel*uDt. The
    // sweep fully owns the position update when it runs, so the plain Euler
    // integration below is skipped — with and without collision a frame moves
    // exactly vel*uDt (the old code integrated first and then swept from the
    // already-moved position, doubling the speed and tunnelling into blocks).
    bool swept = false;
    bool landed = false;
    float collideMode = matMotion.y;
    if (uCollisionOn == 1 && collideMode > 0.5) {
        // Slice selection is position-owned, not emitter-owned: the first baked
        // volume that CONTAINS the particle wins (presence gates unbuilt
        // slices). Two emitters sharing one spec at distant sites can never
        // fight over a header bake index (there is none), and a particle
        // drifting out of its spawn volume keeps colliding inside any other
        // site's volume it enters.
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
        if (slice >= 0) {
            swept = true;
            // ESCAPE: a particle sitting INSIDE a solid voxel used to be stuck
            // forever — the per-axis sweep zeroed every move, and the landed
            // pop-up below only fires on a BLOCKED DOWNWARD step, which a
            // zero-gravity storm member never takes. Embedding happens when
            // the collision volume APPEARS around a free-flying particle
            // (storm activation while the quadrants still build, a block
            // placed onto a member, coverage-boundary re-entry). The escape
            // OWNS this frame's position update (the sweep below is skipped —
            // same "collision owns the move" contract as always) and exits
            // through the nearest opening:
            //   1. ENTRY AXIS first: scan opposite the DOMINANT velocity axis
            //      (back the way the particle came); a free voxel within 8
            //      wins outright and no other face is scanned.
            //   2. otherwise scan all six directions and take the NEAREST
            //      free voxel.
            //   3. no opening within 8 anywhere: bounded up-rise (deep burial
            //      continues next frame).
            // Exiting zeroes ONLY the exit-axis velocity component (vanilla
            // axis-wise collision response — tangential motion survives; the
            // entry-axis component would re-embed the particle). Resting
            // particles are never touched — their positions sit on voxel top
            // faces, which the +0.0001 epsilon in solidAt reads as the free
            // voxel above. Steady-state cost: one texel fetch per particle
            // per frame; the scans only run on genuine embeds.
            bool insideSolid = solidAt(pos, mOrigin, slice);
            if (insideSolid) {
                vec3 escDir = vec3(0.0);
                float escDist = 0.0;
                if (dot(vel, vel) > 1e-6) {
                    vec3 nav = -vel;
                    vec3 d1 = abs(nav.x) >= abs(nav.y) && abs(nav.x) >= abs(nav.z)
                            ? vec3(sign(nav.x), 0.0, 0.0)
                            : (abs(nav.y) >= abs(nav.z)
                                    ? vec3(0.0, sign(nav.y), 0.0)
                                    : vec3(0.0, 0.0, sign(nav.z)));
                    float dist = escapeScan(pos, mOrigin, slice, d1, 8);
                    if (dist > 0.0) {
                        escDir = d1;
                        escDist = dist;
                    }
                }
                if (escDist == 0.0) {
                    float best = 1e9;
                    for (int d = 0; d < 6; d++) {
                        vec3 dir = vec3(0.0);
                        float sgn = (d & 1) == 0 ? 1.0 : -1.0;
                        int axis = d >> 1;
                        if (axis == 0)
                            dir.x = sgn;
                        else if (axis == 1)
                            dir.y = sgn;
                        else
                            dir.z = sgn;
                        float dist = escapeScan(pos, mOrigin, slice, dir, 8);
                        if (dist > 0.0 && dist < best) {
                            best = dist;
                            escDir = dir;
                        }
                    }
                    if (best < 1e8)
                        escDist = best;
                }
                if (escDist > 0.0) {
                    pos += escDir * escDist;
                    // vanilla axis-wise response: only the exit axis stops
                    if (escDir.x != 0.0)
                        vel.x = 0.0;
                    else if (escDir.y != 0.0)
                        vel.y = 0.0;
                    else
                        vel.z = 0.0;
                } else {
                    // no opening within 8 voxels anywhere (deep burial):
                    // bounded up-rise, continues next frame
                    float risen = 0.0;
                    do {
                        pos.y += 0.5;
                        risen += 0.5;
                    } while (risen < 4.0 && solidAt(pos, mOrigin, slice));
                }
            }
            if (!insideSolid) {
                // Each probe resolves one texel, so a single test move larger
                // than ~1 block tunnels straight through a thin wall AND
                // strands the particle inside it (every neighbour then reads
                // solid). Split the frame move into <= half-voxel substeps:
                // storm members capped at STORM_MAX_SPEED over the clamped
                // 0.25 s worst-case frame travel up to 1.5 blocks. Ordinary
                // speeds keep steps at exactly 1.
                // MODEL particles additionally collide as their vanilla AABB
                // (EntityType ALLAY: sized 0.35 x 0.6) — the test point leads
                // the move by the half-width horizontally (0.175: the model
                // surface just touches the wall face instead of overlapping
                // it) and by the full height upward (0.6: the HEAD is tested —
                // a feet-only check never sees a ceiling); downward never
                // leads (the feet ARE the box bottom and land on the floor).
                // Sprites keep the plain point test. The lead shifts the whole
                // substep probe line by a constant, so consecutive samples
                // stay one substep apart (< 1 voxel — no tunneling).
                float marginXZ = isModel ? 0.175 : 0.0;
                float marginYUp = isModel ? 0.6 : 0.0;
                float span = max(abs(vel.x), max(abs(vel.y), abs(vel.z))) * uDt;
                int steps = 1 + int(clamp(span / 0.5, 0.0, 15.0));
                float sdt = uDt / float(steps);
                for (int si = 0; si < steps; si++) {
                    float sx = vel.x * sdt;
                    if (sx != 0.0) {
                        float probe = sx + (sx > 0.0 ? marginXZ : -marginXZ);
                        if (solidAt(pos + vec3(probe, 0.0, 0.0), mOrigin, slice))
                            vel.x = 0.0;
                        else
                            pos.x += sx;
                    }
                    float sy = vel.y * sdt;
                    if (sy != 0.0) {
                        // Up-moves on MODEL particles test the head at BOTH
                        // ends of its substep path (pos+0.6 and pos+sy+0.6):
                        // the head's start can already sit inside a ceiling
                        // reached HORIZONTALLY (x/z probes only test the feet
                        // row), and a single end-of-move probe 0.6 ahead would
                        // then jump clean over a 1-block ceiling (0.5 substep
                        // + 0.6 lead > 1 voxel). Segment ends ≤ 0.5 apart
                        // cannot straddle a 1-thick solid span, so both ends
                        // together are airtight. Feet-after is tested first
                        // for every material (a ledge below the head path is
                        // invisible to the head probes); sprites never pay the
                        // two extra fetches (marginYUp == 0 short-circuits).
                        bool blockedY = solidAt(pos + vec3(0.0, sy, 0.0), mOrigin, slice);
                        if (!blockedY && sy > 0.0 && marginYUp > 0.0) {
                            blockedY = solidAt(pos + vec3(0.0, marginYUp, 0.0), mOrigin, slice)
                                    || solidAt(pos + vec3(0.0, sy + marginYUp, 0.0), mOrigin, slice);
                        }
                        if (blockedY) {
                            vel.y = 0.0;
                            if (sy < 0.0) {
                                landed = true;
                                // if the blocked step's target voxel is the voxel
                                // the particle already sits in (spawned inside a
                                // block), pop it up onto that voxel's top face
                                float top = floor(pos.y + sy + 0.0001) + 1.0;
                                pos.y = max(pos.y, top);
                            }
                        } else {
                            pos.y += sy;
                        }
                    }
                    float sz = vel.z * sdt;
                    if (sz != 0.0) {
                        float probe = sz + (sz > 0.0 ? marginXZ : -marginXZ);
                        if (solidAt(pos + vec3(0.0, 0.0, probe), mOrigin, slice))
                            vel.z = 0.0;
                        else
                            pos.z += sz;
                    }
                    if (landed && collideMode > 1.5)
                        break; // dying leaf: no need to keep sweeping this frame
                }
            }
        }
    }

