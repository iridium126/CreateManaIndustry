package com.iridium126.createmanaindustry.content.allaystorm.rift.sable;
import net.minecraft.server.level.ServerLevel;
import java.util.*;
/** Preparation reserves plot indices without inserting a visible target structure. */
final class RiftPlotReservations {
    private static final Map<ServerLevel,Map<Integer,UUID>> HELD=new IdentityHashMap<>();
    static BitSet occupied(ServerLevel level){BitSet bits=new BitSet();for(int index:HELD.getOrDefault(level,Map.of()).keySet())bits.set(index);return bits;}
    static void hold(ServerLevel level,int index,UUID owner){UUID previous=HELD.computeIfAbsent(level,ignored->new HashMap<>()).putIfAbsent(index,owner);if(previous!=null&&!previous.equals(owner))throw new IllegalStateException("Plot reservation conflict");}
    static void release(UUID owner){for(var entries:HELD.values())entries.values().removeIf(owner::equals);HELD.entrySet().removeIf(e->e.getValue().isEmpty());}
    static void clear(){HELD.clear();}
}
