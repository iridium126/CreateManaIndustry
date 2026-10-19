package com.iridium126.createmanaindustry.content.allaystorm;

import java.util.Arrays;
import com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormWavePacket;

/** CPU packet lifecycle. A fading abort is replaceable; a live attack never is. */
public final class StormWaveSlots {
    private final ClientboundStormWavePacket[] packets;
    public StormWaveSlots(){this(4);}
    public StormWaveSlots(int capacity){if(capacity!=4&&capacity!=68)throw new IllegalArgumentException("Storm slots");packets=new ClientboundStormWavePacket[capacity];}
    public record Update(int slot, ClientboundStormWavePacket packet, boolean replacement) {}
    public ClientboundStormWavePacket packet(int slot) { return packets[slot]; }
    public void complete(int id){for(int i=0;i<packets.length;i++)if(packets[i]!=null&&packets[i].waveId()==id)packets[i]=null;}
    public void clear() { Arrays.fill(packets, null); }
    public void retire(float now) {
        for (int i = 0; i < packets.length; i++) if (packets[i] != null && AllayStarfall.elapsed(now, packets[i].diveUntilSec()) > 0) packets[i] = null;
    }
    public Update apply(ClientboundStormWavePacket incoming, float now) {
        retire(now);
        int slot = -1;
        for (int i = 0; i < packets.length; i++) if (packets[i] != null && packets[i].waveId() == incoming.waveId()) { slot = i; break; }
        if (incoming.abort()) {
            if (slot < 0 || packets[slot].abort()) return null;
            var old = packets[slot];
            float at = old.startSec() + AllayStarfall.elapsed(now, old.startSec());
            var aborted = old.copy(3, 6, at, at + .25f, old.members()).reason(incoming.fallbackReason());
            packets[slot] = aborted;
            return new Update(slot, aborted, false);
        }
        boolean replacement = slot < 0;
        if (replacement) {
            if (incoming.event() != AllayStarfall.START) return null;
            int begin=packets.length==68&&incoming.column()?4:0,end=packets.length==68&&!incoming.column()?4:packets.length;
            for (int i = begin; i < end; i++) if (packets[i] == null) { slot = i; break; }
            if (slot < 0) for (int i = begin; i < end; i++) if (packets[i].abort()) { slot = i; break; }
            if (slot < 0) return null;
        }
        var old = packets[slot];
        if (!replacement && (old.abort() || old.locked() && incoming.event() == AllayStarfall.UPDATE)) return null;
        int[] members = incoming.members().length == 0 && !replacement ? old.members() : incoming.members();
        var merged = incoming.copy(incoming.locked() ? AllayStarfall.LOCK : incoming.event(), incoming.phase(),
                incoming.lockSec(), incoming.diveUntilSec(), members);
        packets[slot] = merged;
        return new Update(slot, merged, replacement);
    }
}
