package com.iridium126.createmanaindustry.client.allaystorm.rift;

import com.iridium126.createmanaindustry.content.allaystorm.StormIdentity;
import com.iridium126.createmanaindustry.content.allaystorm.rift.StormRiftSnapshot;
import java.util.HashSet;
import java.util.Set;

/** Packet ordering is independent of both render frames and member-GPU generations. */
public final class RiftClientState {
    private StormRiftSnapshot current;
    private long sequence = -1;
    private final Set<StormIdentity> retired = new HashSet<>();
    public boolean accept(StormRiftSnapshot snapshot) {
        if (!snapshot.previous().valid() || !snapshot.current().valid() || snapshot.previous().tick()>snapshot.current().tick()
                || !Double.isFinite(snapshot.x()) || !Double.isFinite(snapshot.z()) || !Double.isFinite(snapshot.planeY())
                || snapshot.sequence() < 0 || snapshot.state() < 0 || snapshot.state() > StormRiftSnapshot.UNAVAILABLE) return false;
        if (retired.contains(snapshot.identity())) return false;
        if (current != null && current.identity().equals(snapshot.identity()) && snapshot.sequence() <= sequence) return false;
        if (current != null && !current.identity().equals(snapshot.identity())) {
            retired.add(current.identity()); sequence = -1;
        }
        current = snapshot; sequence = snapshot.sequence();
        return true;
    }
    public StormRiftSnapshot current() { return current; }
    public void clear() { current = null; sequence = -1; retired.clear(); }
}
