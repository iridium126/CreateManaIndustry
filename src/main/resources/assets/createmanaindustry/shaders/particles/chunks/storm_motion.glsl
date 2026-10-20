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
    float cmiStormFrameCap=max(stormA.w,.5);
    bool cmiDomeFollowing=false;vec3 cmiDomeFollowingPoint=vec3(0);
    if (!corpse && stormA.x > 0.5) {
        // ----- typhoon: world-frame servo onto the circulating arm home point
        vec3 anchor = emitters.u[hb + 19u].xyz;
        float spMaxV = cmiAttackSpeed(p0.w,uTimeSec,max(stormA.w, 0.5));
        if(uStarfallEnabled){uint a=cmiStarAssignment(p0.w),member=cmiStarMember(p0.w);bool returning=a==0u&&member<131072u&&(starStateRead.words[member*2u+1u]&255u)>=6u;
            if(a!=0u&&cmiHelix(cmiStarBase(cmiStarSlot(a)))&&cmiStarPhase(a,uTimeSec)==6.)returning=true;
            if(returning)spMaxV=max(spMaxV,length(vel)-24.*uDt);
        }cmiStormFrameCap=spMaxV;
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
        bool columnEngaged=false;uint columnPhase=0u;float columnMarker=0.;
        // One approach per member per wave. The stateless contact test is
        // REVERSIBLE — a member that turned home re-crosses the contact
        // radius within a frame or two and the hash test re-engages it,
        // ping-ponging between "pursue" and "home" on the shell until the
        // window ends (the reported in-place jitter). The FIRST contact
        // frame therefore latches MEMBER_LATCH_BIT into p0.w (survives pool
        // compaction; cleared only by a new storm generation killing the
        // pool), and a latched member never engages again.
        if(uStarfallEnabled){
            // Only a claimed member may replace its typhoon home servo. Do not
            // alias that home with an out parameter: a rejected attack must
            // preserve both the home position and its orbital feed-forward velocity.
            uint phase=0u;vec3 attackTarget,attackVelocity;
            uint assignment=cmiStarAssignment(p0.w);
            if(cmiColumnAssignment(assignment)){
                waveEngaged=cmiColumnSteer(assignment,pos,vel,target,vT,attackTarget,attackVelocity,phase,columnMarker);columnEngaged=waveEngaged;columnPhase=phase;
                roll=columnMarker;
            }else waveEngaged=cmiStarSteering(p0.w,p3.z,pos,target,spMaxV,attackTarget,attackVelocity,phase);
            if(assignment!=0u){
                uint slot=cmiStarSlot(assignment),b=cmiStarBase(slot);
                if(cmiHelix(b)||cmiDome(b))roll=0.;
                uint member=cmiStarMember(p0.w);if((cmiHelix(b)||cmiDome(b))&&starStateRead.words[member*2u]==uint(cmiStarVec(b).x+.5)&&(starStateRead.words[member*2u+1u]&255u)>=6u){phase=6u;waveEngaged=false;}
                if(phase==6u){uint rank=cmiColumnAssignment(assignment)?cmiStarRank(assignment):cmiMajorRank(assignment);atomicOr(starStateWrite.words[CMI_COMPLETIONS+cmiCompletionOffset(slot)+rank/32u],1u<<(rank&31u));}
            }
            if(waveEngaged){target=attackTarget;vT=attackVelocity;}
            uint member=cmiStarMember(p0.w);if(member<131072u){uint a=cmiStarAssignment(p0.w),id=a==0u?0u:uint(cmiStarVec(cmiStarBase(cmiStarSlot(a))).x+.5);if(!cmiColumnAssignment(a)&&id!=0u&&starStateRead.words[member*2u]==id)phase=max(phase,starStateRead.words[member*2u+1u]&255u);if(a==0u&&starStateRead.words[member*2u]!=0u&&(starStateRead.words[member*2u+1u]&255u)>=1u){id=starStateRead.words[member*2u];phase=distance(pos,target)<2.?7u:6u;}starStateWrite.words[member*2u]=id;starStateWrite.words[member*2u+1u]=phase;}
        }else {
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

        }
        // separation + player repulsion, world space.
        // Same visit budget as the boids sweep had.
        uint domeAssignment=uStarfallEnabled?cmiStarAssignment(p0.w):0u;
        bool domeMember=waveEngaged&&domeAssignment!=0u&&cmiDome(cmiStarBase(cmiStarSlot(domeAssignment)));
        bool domeFlow=domeMember&&cmiStarPhase(domeAssignment,uTimeSec)==4.;
        bool domeGather=domeMember&&!domeFlow;
        vec3 domePreviousPoint=pos,domePreviousVelocity=vel;
        if(domeFlow)cmiDomeSample(domeAssignment,uTimeSec-uDt,domePreviousPoint,domePreviousVelocity);
        // Certified channels already separate members of this attack. The
        // ordinary grid remains the sole runtime fallback for actual deviations,
        // moving terrain, other attacks and ordinary returning storm members.
        bool domeOnPath=domeFlow&&cmiStarTime(cmiStarBase(cmiStarSlot(domeAssignment)),uTimeSec)-cmiStarVec(cmiStarBase(cmiStarSlot(domeAssignment))+4u).z>=1.25&&distance(pos,domePreviousPoint)<.02;
        float separationRadius=STORM_SEP_R;if(waveEngaged&&uStarfallEnabled){uint a=cmiStarAssignment(p0.w);if(cmiHelix(cmiStarBase(cmiStarSlot(a)))||cmiDome(cmiStarBase(cmiStarSlot(a))))separationRadius=domeMember?.45:1.4;}
        vec3 sepW = vec3(0.0);
        int sepVisits = 0;
        bool sepBudgetGone = false;
        ivec3 pc = ivec3(floor(pos / CELL_SIZE));
        for (int x = 0; x < 3 && !sepBudgetGone; x++)
        for (int y = 0; y < 3 && !sepBudgetGone; y++)
        for (int z = 0; z < 3 && !sepBudgetGone; z++) {
            ivec3 offset=domeMember?ivec3(x==0?0:x==1?1:-1,y==0?0:y==1?1:-1,z==0?0:z==1?1:-1):ivec3(x-1,y-1,z-1);
            ivec3 c3 = pc + offset;
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
                vec3 d = pos-op;
                float d2 = dot(d, d);
                if(d2>=separationRadius*separationRadius)continue;
                if(domeOnPath){
                    uint otherAssignment=cmiStarAssignment(readBuf.data[uint(j)*4u].w);
                    if(otherAssignment!=0u&&cmiStarSlot(otherAssignment)==cmiStarSlot(domeAssignment)){
                        uint otherMember=cmiStarMember(readBuf.data[uint(j)*4u].w);
                        if(otherMember<131072u&&(starStateRead.words[otherMember*2u+1u]&0x40000000u)!=0u&&starStateRead.words[otherMember*2u]==starStateWrite.words[cmiStarMember(p0.w)*2u])continue;
                    }
                }
                if(d2<=1e-8){d=cmiStormSeparationDelta(d,cmiStarMember(p0.w),cmiStarMember(readBuf.data[uint(j)*4u].w));d2=dot(d,d);}
                if (d2 < separationRadius * separationRadius && d2 > 1e-8) {
                    float dl = sqrt(d2);
                    sepW += d * ((separationRadius - dl) / (dl * separationRadius));
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
        if(!waveEngaged)repW=cmiPlayerRepel(pos,STORM_PLAYER_R,STORM_PLAYER_W);
        // whisker cone bends the engaged steering around obstacles (player
        // roofs/walls) instead of stalling against them
        if (waveEngaged && !columnEngaged && (uCollisionOn == 1||cmiStormVoxel) && !(uStarfallEnabled&&(cmiHelix(cmiStarBase(cmiStarSlot(cmiStarAssignment(p0.w))))||cmiDome(cmiStarBase(cmiStarSlot(cmiStarAssignment(p0.w)))))&&cmiStarPhase(cmiStarAssignment(p0.w),uTimeSec)==4.)) {
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
        if(domeFlow){
            uint member=cmiStarMember(p0.w),a=cmiStarAssignment(p0.w),b=cmiStarBase(cmiStarSlot(a)),j=CMI_DOME_JOIN+(cmiStarSlot(a)*12288u+cmiMajorRank(a))*8u,id=uint(cmiStarVec(b).x+.5);
            bool fresh=starStateRead.words[j]!=id;vec3 offset=vec3(0),delta=vec3(0);float started;
            if(fresh){offset=pos-domePreviousPoint;delta=vel-domePreviousVelocity;started=uTimeSec-uDt;}else{offset=vec3(uintBitsToFloat(starStateRead.words[j+1u]),uintBitsToFloat(starStateRead.words[j+2u]),uintBitsToFloat(starStateRead.words[j+3u]));delta=vec3(uintBitsToFloat(starStateRead.words[j+4u]),uintBitsToFloat(starStateRead.words[j+5u]),uintBitsToFloat(starStateRead.words[j+6u]));started=uintBitsToFloat(starStateRead.words[j+7u]);}
            starStateWrite.words[j]=id;for(uint k=0u;k<3u;k++){starStateWrite.words[j+1u+k]=floatBitsToUint(offset[k]);starStateWrite.words[j+4u+k]=floatBitsToUint(delta[k]);}starStateWrite.words[j+7u]=floatBitsToUint(started);
            float age=clamp(cmiStarTime(b,uTimeSec)-cmiStarTime(b,started),0.,1.),h=1.-3.*age*age+2.*age*age*age,g=age-2.*age*age+age*age*age;
            target+=offset*h+delta*g;vT+=offset*(-6.*age+6.*age*age)+delta*(1.-4.*age+3.*age*age);
            cmiDomeFollowing=domeOnPath;cmiDomeFollowingPoint=target;vel=(target-pos)/max(uDt,.0001)+sepW*.5;float speed=length(vel);vel=speed>.000001?vel*(6./speed):normalize(vT)*6.;
        }else if(domeGather){
            // Bounded interception of a moving ring; a same-speed tail chase never catches it.
            uint a=cmiStarAssignment(p0.w),b=cmiStarBase(cmiStarSlot(a));vec4 ring=cmiDomeRing(a);vec3 center=cmiDomeCenter(b,uTimeSec)+vec3(0,ring.y-.3,0);
            float phi=ring.z+cmiStarVec(b+20u).z+6.*(cmiStarTime(b,uTimeSec-uDt)-cmiStarVec(b+4u).x)/ring.x;float lo=0.,hi=(distance(pos,center)+ring.x)/6.+.05;
            for(int k=0;k<10;k++){float mid=(lo+hi)*.5;float angle=phi+6.*mid/ring.x;vec3 future=center+vec3(cos(angle),0,sin(angle))*ring.x;if(distance(pos,future)>6.*mid)lo=mid;else hi=mid;}
            float angle=phi+6.*max(hi,uDt)/ring.x;vec3 future=center+vec3(cos(angle),0,sin(angle))*ring.x,desired=future-pos+sepW*.04;
            float speed=length(desired);if(speed>.000001)desired*=6./speed;else desired=vT;vel=desired;
        }else if(columnEngaged)vel=vT;else vel += aW * uDt;
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
        // Identity capacity is independent of the shared pool. Minimal bindings
        // are valid when no correction storage has been requested.
        if (memberIdx < uint(correction.c.length()) / 2u) {
            uint ci = memberIdx * 2u;
            vec4 cA = correction.c[ci];
            vec4 cB = correction.c[ci + 1u];
            float cage = uTimeSec - cA.w;
            if (cB.w > 0.5 && cage >= 0.0) {
                bool hitC = cB.w > 2.5;
                float window = hitC ? 0.25 : 1.5;
                uint assignment=cmiStarAssignment(p0.w);
                if (cage <= window && (hitC||!cmiColumnAssignment(assignment))) {
                    vec3 target = cA.xyz + cB.xyz * cage; // velocity extrapolation
                    float ramp = hitC ? 1.0 : (0.25 + 0.75 * clamp(cage / window, 0.0, 1.0));
                    float k = (hitC ? 24.0 : 8.0) * ramp;
                    vel += (target - pos) * min(k * uDt, 0.5);
                    vel += (cB.xyz - vel) * min(2.5 * uDt, 0.35);
                    // Network correction follows the servo; it must obey the same motion cap.
                    float cap=cmiStormFrameCap,speed=length(vel);
                    if(speed>cap)vel*=cap/speed;
                }
            }
        }
    }

