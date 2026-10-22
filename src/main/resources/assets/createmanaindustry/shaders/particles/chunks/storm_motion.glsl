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
    bool cmiDomeFollowing=false,cmiDomeGatherSampleValid=false;vec3 cmiDomeFollowingPoint=vec3(0),cmiDomePoseTangent=vel;
    if (!corpse && stormA.x > 0.5) {
        // ----- typhoon: world-frame servo onto the circulating arm home point
        vec3 anchor = emitters.u[hb + 19u].xyz;
        float ordinaryCap=max(stormA.w,.5);
        float spMaxV = cmiAttackSpeed(p0.w,uTimeSec,ordinaryCap);
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
                uStormConvPhase, uStormConvRate, ordinaryCap, anchor, target, vT);
        // target/vT hold the ordinary snapshot. Attack out parameters are
        // separate and replace it ONLY for an engaged member; released members
        // retain it without keeping a second pair of vectors live in the kernel.
        bool domeHandoff=false;float handoffAge=0.;

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
            }else{
                uint member=cmiStarMember(p0.w);
                bool ended=assignment!=0u&&member<131072u
                    &&starStateRead.words[member*2u]==uint(cmiStarVec(cmiStarBase(cmiStarSlot(assignment))).x+.5)
                    &&(starStateRead.words[member*2u+1u]&255u)>=6u;
                // Finished members must not sample an active slot's route again.
                if(ended)phase=6u;
                else waveEngaged=cmiStarSteering(p0.w,p3.z,pos,target,spMaxV,attackTarget,attackVelocity,phase);
            }
            if(assignment!=0u){
                uint slot=cmiStarSlot(assignment),b=cmiStarBase(slot);
                if(cmiHelix(b)||cmiDome(b))roll=0.;
                if(phase==6u){uint rank=cmiColumnAssignment(assignment)?cmiStarRank(assignment):cmiMajorRank(assignment);atomicOr(starStateWrite.words[CMI_COMPLETIONS+cmiCompletionOffset(slot)+rank/32u],1u<<(rank&31u));}
            }
            if(waveEngaged){target=attackTarget;vT=attackVelocity;}
            uint member=cmiStarMember(p0.w);
            if(member<131072u){
                uint a=assignment,oldId=starStateRead.words[member*2u],oldFlags=starStateRead.words[member*2u+1u],oldPhase=oldFlags&255u;
                uint id=a==0u?0u:uint(cmiStarVec(cmiStarBase(cmiStarSlot(a))).x+.5);
                bool wasDome=(oldFlags&0x100u)!=0u;
                bool ownsDome=a!=0u&&!cmiColumnAssignment(a)&&cmiDome(cmiStarBase(cmiStarSlot(a)));
                if(!cmiColumnAssignment(a)&&id!=0u&&oldId==id)phase=max(phase,oldPhase);
                if(a==0u&&oldId!=0u&&oldPhase>=1u){id=oldId;phase=distance(pos,target)<2.?7u:6u;}
                bool releasedDome=!waveEngaged&&(ownsDome||(a==0u&&wasDome));
                if(releasedDome){
                    spMaxV=ordinaryCap;roll=0.;
                    if(phase>=6u&&(oldFlags&0x200u)==0u){
                        // Finish the velocity handoff even when already inside
                        // the home radius; phase 7 owns the later pose-fade clock.
                        phase=6u;
                        handoffAge=oldId==id&&oldPhase==6u?uintBitsToFloat(starStateRead.words[CMI_GATHER_BLOCKED+member]):0.;
                        domeHandoff=handoffAge<.5;
                        starStateWrite.words[CMI_GATHER_BLOCKED+member]=floatBitsToUint(min(.5,handoffAge+uDt));
                    }
                    if(oldPhase!=phase)starStateWrite.words[CMI_GATHER_STREAK+member]=0u;
                }
                // Bit 8 records lifecycle provenance, never display-cache validity.
                uint kind=(ownsDome||(a==0u&&wasDome))?0x100u:0u;
                if(releasedDome&&((oldFlags&0x200u)!=0u||phase==7u||handoffAge+uDt>=.5))kind|=0x200u;
                starStateWrite.words[member*2u]=id;
                starStateWrite.words[member*2u+1u]=phase|kind|((phase==4u&&id==oldId)?(oldFlags&(CMI_DOME_INSIDE_BOUND|0x20000000u)):0u);
            }
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
        if(domeGather){cmiDomeGatherSampleValid=true;cmiDomeFollowingPoint=target;cmiDomePoseTangent=vT;}
        vec3 domePreviousPoint=pos,domePreviousVelocity=vel;
        if(domeFlow)cmiDomeSampleOffset(domeAssignment,-uDt,domePreviousPoint,domePreviousVelocity);
        // Certified channels already separate members of this attack. The
        // ordinary grid remains the sole runtime fallback for actual deviations,
        // moving terrain, other attacks and ordinary returning storm members.
        float separationRadius=STORM_SEP_R;if(waveEngaged&&uStarfallEnabled){uint a=cmiStarAssignment(p0.w);if(cmiHelix(cmiStarBase(cmiStarSlot(a)))||cmiDome(cmiStarBase(cmiStarSlot(a))))separationRadius=domeMember?.45:1.4;}
        vec3 sepW = vec3(0.0);
        int sepVisits = 0;
        bool sepBudgetGone = false;
        ivec3 pc = ivec3(floor(pos / CELL_SIZE));
        // Dome uses the single post-integration candidate hash and swept-contact
        // pass. It must not also receive an earlier boids position correction.
        for (int x = 0; x < 3 && !sepBudgetGone && !domeMember; x++)
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
        if (waveEngaged && !columnEngaged && !domeGather && (uCollisionOn == 1||cmiStormVoxel) && !(uStarfallEnabled&&(cmiHelix(cmiStarBase(cmiStarSlot(cmiStarAssignment(p0.w))))||cmiDome(cmiStarBase(cmiStarSlot(cmiStarAssignment(p0.w)))))&&cmiStarPhase(cmiStarAssignment(p0.w),uTimeSec)==4.)) {
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
            bool fresh=starStateRead.words[j]!=id;vec3 offset=vec3(0),delta=vec3(0);float started,initialSpeed;
            if(fresh){offset=pos-domePreviousPoint;delta=vel-domePreviousVelocity;started=cmiDomeElapsed(b,uTimeSec)-uDt;initialSpeed=length(vel);}else{offset=vec3(uintBitsToFloat(starStateRead.words[j+1u]),uintBitsToFloat(starStateRead.words[j+2u]),uintBitsToFloat(starStateRead.words[j+3u]));vec2 xy=unpackHalf2x16(starStateRead.words[j+4u]);delta=vec3(xy,uintBitsToFloat(starStateRead.words[j+5u]));initialSpeed=uintBitsToFloat(starStateRead.words[j+6u]);started=uintBitsToFloat(starStateRead.words[j+7u]);}
            starStateWrite.words[j]=id;for(uint k=0u;k<3u;k++)starStateWrite.words[j+1u+k]=floatBitsToUint(offset[k]);starStateWrite.words[j+4u]=packHalf2x16(delta.xy);starStateWrite.words[j+5u]=floatBitsToUint(delta.z);starStateWrite.words[j+6u]=floatBitsToUint(initialSpeed);starStateWrite.words[j+7u]=floatBitsToUint(started);
            float age=clamp(cmiDomeElapsed(b,uTimeSec)-started,0.,1.),previousAge=clamp(age-uDt,0.,1.);
            float h=1.-age*age*age*(10.-15.*age+6.*age*age),g=age-age*age*age*(6.-8.*age+3.*age*age);
            float ph=1.-previousAge*previousAge*previousAge*(10.-15.*previousAge+6.*previousAge*previousAge),pg=previousAge-previousAge*previousAge*previousAge*(6.-8.*previousAge+3.*previousAge*previousAge);
            target+=offset*h+delta*g;domePreviousPoint+=offset*ph+delta*pg;
            vT+=offset*(-30.*age*age+60.*age*age*age-30.*age*age*age*age)+delta*(1.-18.*age*age+32.*age*age*age-15.*age*age*age*age);
            cmiDomeFollowing=true;
            cmiDomeFollowingPoint=target;cmiDomePoseTangent=vT;
            // Reference displacement is feed-forward, not a position snap. The
            // bounded servo removes accumulated integration error without chasing
            // the reference faster than six metres per second.
            vec3 error=domePreviousPoint-pos;float errorLength=length(error);
            vec3 correction=errorLength>.000001?error*min(6.,.6/errorLength):vec3(0);
            bool referenceEnded=cmiDomeElapsed(b,uTimeSec)-(cmiStarVec(b+4u).z-cmiStarVec(b+4u).x)>=cmiDomeRecord(a).z;
            vec3 desired=(referenceEnded?vT:(target-domePreviousPoint)/max(uDt,.0001))+correction;
            float currentSpeed=length(vel),u=clamp(age/.5,0.,1.),speedGoal=mix(initialSpeed,6.,u*u*u*(10.-15.*u+6.*u*u));
            float nextSpeed=clamp(min(speedGoal,length(desired)),max(0.,currentSpeed-18.*uDt),currentSpeed+18.*uDt);
            vec3 direction=length(desired)>.000001?normalize(desired):normalize(vT);
            if(currentSpeed>.0001&&nextSpeed>.0001){float cosine=clamp((currentSpeed*currentSpeed+nextSpeed*nextSpeed-324.*uDt*uDt)/(2.*currentSpeed*nextSpeed),-1.,1.);float rate=min(3.141592654,acos(cosine)/max(uDt,.0001));direction=cmiDomeTurn(vel/currentSpeed,direction,uDt,.0001,rate);}
            vel=direction*nextSpeed;
        }else if(domeGather){
            // Predictively intercept a closed 3-D streamline; once close,
            // integrate its displacement instead of chasing its moving point.
            uint a=cmiStarAssignment(p0.w),b=cmiStarBase(cmiStarSlot(a));
            vec3 previous,previousVelocity;cmiDomeSampleOffset(a,-uDt,previous,previousVelocity);
            vec3 error=previous-pos,desired;float errorLength=length(error);
            if(errorLength<1.)desired=(target-previous)/max(uDt,.0001)+error*min(6.,.6/max(errorLength,.000001));
            else{
                vec4 ring=cmiDomeRing(a);vec3 center=cmiDomeCenter(b,uTimeSec)+vec3(0,ring.y-.3,0);
                float lo=0.,hi=(distance(pos,center)+ring.x+.6)/6.+.1;
                for(int k=0;k<10;k++){float mid=(lo+hi)*.5;vec3 future,motion;cmiDomeSampleOffset(a,mid-uDt,future,motion);if(distance(pos,future)>6.*mid)lo=mid;else hi=mid;}
                vec3 future,motion;cmiDomeSampleOffset(a,max(hi,uDt)-uDt,future,motion);desired=(future-pos)/max(hi,uDt);
            }
            // Avoidance is applied AFTER interception; do not reconstruct the
            // original straight path after choosing a traversable corridor.
            if(cmiStormVoxel&&length(desired)>.0001)desired=cmiDomeGatherAvoid(pos,normalize(desired),min(2.,max(.25,length(desired)*.35)))*length(desired);
            float desiredSpeed=min(6.,length(desired)),oldSpeed=length(vel),newSpeed=clamp(desiredSpeed,max(0.,oldSpeed-18.*uDt),oldSpeed+18.*uDt);
            vec3 direction=length(desired)>.000001?normalize(desired):vec3(0,0,1);
            if(oldSpeed>.0001&&newSpeed>.0001){float cosine=clamp((oldSpeed*oldSpeed+newSpeed*newSpeed-324.*uDt*uDt)/(2.*oldSpeed*newSpeed),-1.,1.);direction=cmiDomeTurn(vel/oldSpeed,direction,uDt,.0001,min(3.141592654,acos(cosine)/max(uDt,.0001)));}
            vel=direction*newSpeed;
        }else if(columnEngaged)vel=vT;else{
            vec3 candidate=vel+aW*uDt;
            if(domeHandoff){
                float speed=length(candidate);if(speed>spMaxV)candidate*=spMaxV/speed;
                float t=clamp(handoffAge/.5,0.,1.),next=clamp((handoffAge+uDt)/.5,0.,1.);
                float w=t*t*t*(10.-15.*t+6.*t*t),nw=next*next*next*(10.-15.*next+6.*next*next);
                // Incremental quintic blend needs only the last actual velocity.
                // It ends after .5 s regardless of visual pose-cache lifetime.
                candidate=mix(p1.xyz,candidate,clamp((nw-w)/max(1.-w,.000001),0.,1.));
            }
            vel=candidate;
        }
        cmiStormFrameCap=spMaxV;
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

