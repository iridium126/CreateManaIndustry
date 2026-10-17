// The cursor is private to the candidate until its dispatch barrier. Single-record
// appends need no CAS retry: overflow returns no output and saturates the cursor.
// No consumer may observe this buffer before the producer dispatch completes.
uint cmiReservePool(uint requested, bool whole, out uint granted) {
    if(requested==1u) {
        uint slot=atomicAdd(counter.writeSlot,1u);
        granted=slot<uCapacity?1u:0u;
        if(granted==0u){atomicMin(counter.writeSlot,uCapacity);return uCapacity;}
        return slot;
    }
    uint seen=atomicAdd(counter.writeSlot,0u);
    for(;;) {
        uint available=uCapacity-min(seen,uCapacity);
        granted=whole&&requested>available?0u:min(requested,available);
        if(granted==0u)return uCapacity;
        uint previous=atomicCompSwap(counter.writeSlot,seen,seen+granted);
        if(previous==seen)return seen;
        seen=previous;
    }
}
uint cmiReserveParticle() { uint granted;return cmiReservePool(1u,true,granted); }
