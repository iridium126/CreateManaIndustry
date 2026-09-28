// Included in update.comp; shares its declared simulation state.
    // ---- damage queue (MODEL): local + server-relayed attacks land here ----
    float hurt = p2.w; // MODEL: hurt-flash timer; other materials: intensity
    // p1.w (billboard roll, unused by the model path) doubles as the packed
    // light stash once a damage entry lands; the death chain reads it.
    float roll = p1.w;
    if (isModel) {
        // storm gate rides the HEADER storm slot (18.x, same gate as the
        // memberMap write above): p0.w identity is memberIdx+1, which collides
        // with a non-storm MODEL's constant 1.0 size multiplier exactly at
        // member 0 -- a p0.w >= 1.5 test silently drops member 0 from the
        // whole damage pipeline
        bool isStorm = stormA.x > 0.5;
        uint n = min(damageQ.header.x, uint(DAMAGE_QUEUE_CAP));
        for (uint i = 0u; i < n; i++) {
            uint ob = i * DAMAGE_ENTRY_WORDS;
            float flags = damageQ.entries[ob + 5u];
            bool memberKey = mod(flags, 2.0) >= 0.5; // bit 0: key is member id
            bool died = flags >= 2.5;                // bit 1: server death verdict
            uint key = uint(damageQ.entries[ob + 0u] + 0.5);
            if (memberKey) {
                // storm entry: match by MEMBER identity, immune to pool
                // recompaction; a corpse consumes it idempotently (a second
                // entry must never restart the death countdown)
                if (!isStorm || hp <= 0.0 || key != ((uint(p0.w + 0.5) & MEMBER_IDX_MASK) - 1u))
                    continue;
            } else {
                if (isStorm || floatBitsToUint(damageQ.entries[ob + 6u]) == 0u
                        || identities.token[uIdentityReadBase + idx] != floatBitsToUint(damageQ.entries[ob + 6u]))
                    continue;
            }
            bool wasAlive = hp > 0.0;
            hp -= damageQ.entries[ob + 1u];
            // vanilla LivingEntity.knockback (airborne target):
            // vel = vel/2 - dir*strength -- the CPU pre-multiplies
            // dir*strength into the entry's zw
            vel.x = vel.x * 0.5 - damageQ.entries[ob + 2u];
            vel.z = vel.z * 0.5 - damageQ.entries[ob + 3u];
            hurt = 1.0;
            roll = damageQ.entries[ob + 4u]; // packed light at the hit
            // the server's died bit is VERDICT, not arithmetic: mid-fight
            // joiners have a full-HP local mirror, so clamp to the corpse
            // state unconditionally (legacy entries keep the old semantics)
            if ((died && memberKey) || (wasAlive && hp <= 0.0))
                hp = 0.0; // kill frame: start the death countdown at zero
        }
    }
