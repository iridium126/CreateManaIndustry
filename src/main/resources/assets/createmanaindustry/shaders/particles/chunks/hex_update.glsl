// Included in update.comp; shares its declared simulation state.
    if (emitters.u[hb + 7u].x > 3.5) {
        uint handle = floatBitsToUint(p3.z);
        if (!hexValid(handle)) return;
        uint metaSlot = handle & 4095u, b = hexBase(metaSlot);
        uint dst = atomicAdd(counter.writeSlot, 1u);
        if (dst >= uCapacity) { return; }
        vec3 anchor = hexInput.v[HEX_ANCHORS + floatBitsToUint(hexInput.v[b].w)].xyz;
        writeBuf.data[dst*4u] = vec4(anchor,hexInput.v[b+1u].w);
        writeBuf.data[dst*4u+1u] = vec4(0);
        // The small reconciliation dispatch refreshes color once per holder.
        // Keep pigment FP64 arithmetic out of the general million-particle update kernel.
        writeBuf.data[dst*4u+2u] = p2;
        writeBuf.data[dst*4u+3u] = vec4(p3.x+uDt,p3.y,p3.z,p3.w);
        hexLive.index[metaSlot] = dst+1u;
        return;
    }

