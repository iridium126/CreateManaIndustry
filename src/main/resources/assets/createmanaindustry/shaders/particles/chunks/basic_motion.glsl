// Included in update.comp; shares its declared simulation state.
    if (!corpse) {
        if (isModel) {
            // vanilla allay regen: heal(1.0) every 10 ticks = 2 HP/s, capped
            // at MAX_HEALTH (Allay.createAttributes); corpses never regen
            hp = min(20.0, hp + 2.0 * uDt);
        }
        vel += gravity * uDt;
        vel += accel * uDt;
        vel += windDir * (windStrength * uDt);
        vel *= max(0.0, 1.0 - drag * uDt);

        // Vanilla cherry-style spiral flutter: horizontal accel growing as age^1.25.
        // Vanilla does xd += cos(seed*60deg) * A * f^1.25 * 0.0025 per tick (A = 2.0,
        // xd in blocks/tick). Converting to blocks/s: 1 blocks/tick = 20 blocks/s and
        // 1 tick = 0.05 s, so the equivalent continuous acceleration is
        // A * f^1.25 * (0.0025 * 20 * 20) = A * f^1.25 * 1.0 blocks/s^2.
        float flutter = matMotion.z;
        if (flutter > 0.0) {
            // hp holds maxLife for non-MODEL materials (flutter is sprite-only)
            float fr = clamp(age / max(hp, 1e-5), 0.0, 1.0);
            // flutter phase: vanilla uses nextFloat·60°. The stored seed is the
            // WIDE per-particle value (emit.comp wideSeed), so the phase rides a
            // hash fold — a raw ×60° at ~7e5 would trash float sin/cos precision
            float ang = radians(cmiHash1(p3.z) * 60.0);
            float acc = flutter * pow(fr, 1.25);
            vel.x += cos(ang) * acc * uDt;
            vel.z += sin(ang) * acc * uDt;
        }
    } else {
        // vanilla death physics: the Allay OVERRIDES travel (Allay.travel) with
        // NO gravity term anywhere — just move() + a uniform velocity scale of
        // 0.91/tick on ALL axes, continuous drag -ln(0.91)*20 = 1.88621/s.
        // LivingEntity never calls applyGravity (that is projectile/item/TNT
        // territory) and the standard travel gravity path is bypassed by the
        // override, so a corpse does NOT fall: it decays to a stop where it was
        // killed, riding only the kill impulse as it decays.
        vel *= max(0.0, 1.0 - 1.88621 * uDt);
    }

