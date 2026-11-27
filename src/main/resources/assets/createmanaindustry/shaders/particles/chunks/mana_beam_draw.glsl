#pragma cmi_include chunks/mana_beam.glsl

vec3 manaRight(vec3 axis) {
    vec3 up = abs(axis.y) < 0.95 ? vec3(0, 1, 0) : vec3(1, 0, 0);
    return normalize(cross(axis, up));
}

vec3 manaStripSide(vec3 axis, vec3 centre) {
    vec3 side = cross(axis, centre);
    if (dot(side, side) < 0.0001) {
        vec3 guide = abs(dot(axis, uCamRight)) < 0.95 ? uCamRight : uCamUp;
        side = guide - axis * dot(guide, axis);
    }
    return normalize(side);
}

vec3 manaArcNode(float station, float age, float seed, bool reverse, vec3 right, vec3 up) {
    float hash = manaRandom(seed * 4093.0 + station * 17.11 + (reverse ? 71.0 : 0.0));
    float angle = (reverse ? -1.0 : 1.0) * (station * 1.12 - age * 4.2)
            + (reverse ? 3.141593 : 0.0) + hash * 1.45;
    float taper = mix(0.17, 1.0, smoothstep(0.0, 3.8, station));
    float radius = taper * (0.58 + 0.69 * hash + 0.13 * sin(age * 12.0 + station * 2.3));
    return radius * (right * cos(angle) + up * sin(angle));
}

void cmiDrawManaBeam(vec4 p0, vec4 p1, vec4 p2, vec4 p3, vec2 corner) {
    vec3 axis = p1.xyz;
    float range = p1.w;
    float age = p3.x;
    float head = manaHeadDistance(range, age);
    float arrived = age - range / MANA_SPEED;
    uint role = uint(p2.z);
    float anchor = p2.x;
    float station = anchor;
    vec3 relative = p0.xyz - uCamPos;
    vec3 delta = vec3(0);
    vec3 color = vec3(0.69, 0.24, 1.0);
    float alpha = manaEnvelope(range, age);
    float style = 5.0;
    vec2 uv = corner;
    vec2 q = corner * 2.0 - 1.0;

    if (role == MANA_CORE) {
        station = corner.x * head;
        delta = axis * (station - anchor) + manaStripSide(axis, relative) * q.y * 1.65;
        if (head <= 0.0) alpha = 0.0;
    } else if (role == MANA_ARC || role == MANA_REVERSE_ARC) {
        style = 6.0;
        color = role == MANA_ARC ? vec3(0.79, 0.36, 1.0) : vec3(1.0, 0.72, 0.31);
        float start = p2.x;
        float end = p2.y;
        anchor = (start + end) * 0.5;
        station = mix(start, min(end, max(head, start)), corner.x);
        vec3 right = manaRight(axis);
        vec3 up = cross(axis, right);
        bool reverse = role == MANA_REVERSE_ARC;
        vec3 a = axis * (start - anchor) + manaArcNode(start, age, p3.z, reverse, right, up);
        vec3 b = axis * (end - anchor) + manaArcNode(end, age, p3.z, reverse, right, up);
        float fraction = (station - start) / max(end - start, 0.00001);
        vec3 side = manaStripSide(normalize(b - a), relative + (a + b) * 0.5);
        delta = mix(a, b, fraction) + side * q.y * (reverse ? 0.12 : 0.19);
        if (head <= start) alpha = 0.0;
    } else if (role == MANA_ORIGIN_RING) {
        style = 7.0;
        // Rose outer ring; the fragment shades the inner ring, runes and
        // champagne hexagram with their own tints.
        color = vec3(1.0, 0.30, 0.52);
        station = min(anchor, head);
        float expand = smoothstep(0.0, 0.22, age);
        float flare = p2.w != 0.0 ? exp(-10.0 * max(0.0, arrived)) * step(0.0, arrived) : 0.0;
        float radius = mix(0.48, 2.0, expand);
        radius *= mix(0.48, 1.0, smoothstep(0.0, 4.0, range));
        vec3 right = manaRight(axis);
        vec3 up = cross(axis, right);
        float angle = age * 2.7 + p3.z * MANA_TAU;
        vec2 spun = mat2(cos(angle), sin(angle), -sin(angle), cos(angle)) * q;
        delta = axis * (station - anchor) + (right * spun.x + up * spun.y) * radius;
        alpha *= (0.41 + 0.20 * expand + 0.18 * flare) * smoothstep(0.0, 0.05, age);
    } else if (role == MANA_HEAD) {
        style = 8.0;
        station = head;
        float impactFlash = p2.w != 0.0 ? step(0.0, arrived) * exp(-11.0 * max(0.0, arrived)) : 0.0;
        float radius = mix(0.32, 1.16, smoothstep(0.0, 0.16, age)) + impactFlash * 0.60;
        radius *= mix(0.52, 1.0, smoothstep(0.0, 5.0, range));
        delta = axis * (station - anchor) + (uCamRight * q.x + uCamUp * q.y) * radius;
        alpha *= (0.65 + 0.22 * impactFlash) * smoothstep(0.0, 0.04, age);
    } else {
        style = 9.0;
        color = vec3(1.0, 0.69, 0.28);
        float seed = p3.z * 1439.0 + p2.y * 13.73;
        float random = manaRandom(seed);
        float angle = MANA_TAU * manaRandom(seed + 71.7);
        bool impact = role == MANA_IMPACT;
        float localAge = impact ? arrived : age - anchor / MANA_SPEED;
        float t = max(0.0, localAge);
        float life = impact ? mix(0.30, 0.72, random) : mix(0.40, 1.00, random);
        float radius = mix(0.055, 0.24, random * random) * (impact ? 1.45 : 1.0);
        if (impact) {
            vec3 normal = manaHitNormal(p2.w);
            vec3 right = manaRight(normal);
            vec3 tangent = right * cos(angle) + cross(normal, right) * sin(angle);
            delta = normal * (0.025 + t * mix(0.65, 2.55, random))
                    + tangent * t * mix(1.2, 4.0, manaRandom(seed + 51.0));
            delta.y -= 0.73 * t * t;
            delta += normal * max(0.0, 0.025 - dot(delta, normal));
        } else {
            vec3 right = manaRight(axis);
            vec3 up = cross(axis, right);
            angle += t * mix(-2.8, 2.8, random);
            float drift = mix(0.23, 0.83, manaRandom(seed + 18.1)) + t * mix(0.5, 1.45, random);
            delta = (right * cos(angle) + up * sin(angle)) * drift - axis * min(anchor, t * 0.7);
            color = mix(color, vec3(0.80, 0.39, 1.0), step(0.39, random));
        }
        delta += (uCamRight * q.x + uCamUp * q.y) * radius;
        alpha *= (0.19 + 0.47 * random) * smoothstep(0.0, 0.045, localAge)
                * (1.0 - smoothstep(life * 0.23, life, localAge));
    }

    vec3 position = relative + delta;
    gl_Position = alpha > 0.0001 ? ProjMat * ModelViewMat * vec4(position, 1.0) : vec4(0, 0, 2, 1);
    vUv = uv;
    vColor = color;
    vAlpha = alpha;
    vDist = length(position);
    vHex = style;
    vMana = vec4(manaHitPlane(delta, axis, anchor, range, p2.w), station, age, head);
}
