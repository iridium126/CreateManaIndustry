#pragma cmi_include chunks/mana_beam.glsl

shared uint manaFirstSlot;
shared uint manaGranted;

// One workgroup reserves the whole cast before assigning roles. A partially
// full pool therefore still gets the core, head and muzzle ring before decoration.
void cmiEmitManaBeam(uint row) {
    uint command = row * 12u;
    uint lane = gl_LocalInvocationID.x;
    if (lane == 0u) {
        uint granted;
        manaFirstSlot = cmiReservePool(uint(emitCmd.entries[command + 3u]), false, granted);
        manaGranted = granted;
    }
    barrier();
    if (lane >= manaGranted) return;

    vec3 origin = vec3(emitCmd.entries[command], emitCmd.entries[command + 1u], emitCmd.entries[command + 2u]);
    uint emitter = uint(emitCmd.entries[command + 4u]);
    float seed = emitCmd.entries[command + 5u];
    vec3 axis = normalize(vec3(emitCmd.entries[command + 8u], emitCmd.entries[command + 9u], emitCmd.entries[command + 10u]));
    float range = emitCmd.entries[command + 11u];
    uint params = uEmitCount * 12u + uint(emitCmd.entries[command + 7u]) * 48u;
    vec3 normal = vec3(emitCmd.entries[params], emitCmd.entries[params + 1u], emitCmd.entries[params + 2u]);
    float face = dot(normal, vec3(1.0, 2.0, 3.0));
    float initialAge = max(0.0, emitCmd.entries[params + 3u]);
    uint detail = manaGranted - min(manaGranted, 3u);
    uint impactCount = face == 0.0 ? 0u : min(40u, min(detail, max(1u, detail / 4u)));
    if (range < 0.01 && face != 0.0) impactCount = detail;
    uint arcSegments = min(uint(ceil(range / 1.25)), (detail - impactCount) / 4u);
    uint trailCount = detail - impactCount - arcSegments * 2u;
    float lifetime = range / MANA_SPEED + MANA_HOLD + MANA_FADE;

    for (uint i = lane; i < manaGranted; i += gl_WorkGroupSize.x) {
        uint role = i;
        float station = range * 0.5;
        float field = 0.0;
        float radius = station + 2.7;
        if (i == MANA_ORIGIN_RING) {
            station = min(2.65, range * 0.36);
            radius = 2.05;
        } else if (i >= 3u) {
            uint index = i - 3u;
            if (index < arcSegments * 2u) {
                uint segment = index % arcSegments;
                role = index < arcSegments ? MANA_ARC : MANA_REVERSE_ARC;
                station = range * float(segment) / float(arcSegments);
                field = range * float(segment + 1u) / float(arcSegments);
                radius = (field - station) * 0.5 + 2.35;
            } else {
                index -= arcSegments * 2u;
                role = index < impactCount ? MANA_IMPACT : MANA_TRAIL;
                field = float(i);
                if (role == MANA_IMPACT) {
                    station = range;
                    radius = 4.3;
                } else {
                    float jitter = mix(0.1, 0.9, manaRandom(seed * 1441.0 + field * 19.37));
                    station = range * (float(index - impactCount) + jitter) / float(trailCount);
                    radius = 3.5;
                }
            }
        }
        float anchor = role == MANA_ARC || role == MANA_REVERSE_ARC ? (station + field) * 0.5 : station;
        // Sparks fade out before the beam does; retire them at that exact
        // moment instead of holding the pool slot for the full envelope.
        float life = lifetime;
        if (role == MANA_TRAIL) {
            float fade = manaRandom(seed * 1439.0 + field * 13.73);
            life = min(life, station / MANA_SPEED + mix(0.40, 1.00, fade));
        } else if (role == MANA_IMPACT) {
            float fade = manaRandom(seed * 1439.0 + field * 13.73);
            life = min(life, range / MANA_SPEED + mix(0.30, 0.72, fade));
        }
        uint slot = manaFirstSlot + i;
        uint base = slot * 4u;
        writeBuf.data[base] = vec4(origin + axis * anchor, radius);
        writeBuf.data[base + 1u] = vec4(axis, range);
        writeBuf.data[base + 2u] = vec4(station, field, float(role), face);
        writeBuf.data[base + 3u] = vec4(initialAge, life, seed, uintBitsToFloat(emitter));
        cmiHistorySnap(slot);
        if (uFgOrdinaryIds) cmiNewIdentity(slot);
    }
}
