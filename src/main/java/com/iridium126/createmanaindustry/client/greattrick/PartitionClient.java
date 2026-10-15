package com.iridium126.createmanaindustry.client.greattrick;

import com.iridium126.createmanaindustry.compat.trickster.net.ClientboundPartitionPacket;
import com.iridium126.createmanaindustry.compat.trickster.partition.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.util.*;

/** Logical barriers outlive neither their server removal nor a world change; visual tails are separate. */
public final class PartitionClient {
    public static final class Field {
        public final UUID id;public final PartitionBounds bounds;public final float born;public float stopped=-1;public final boolean animate;
        Field(ClientboundPartitionPacket p){id=p.id();bounds=p.bounds();born=now();animate=p.animate();}
    }
    private static Level level;
    private static final Map<UUID,Field> FIELDS=new LinkedHashMap<>();
    private static final List<ClientboundPartitionPacket> PENDING=new ArrayList<>();
    private PartitionClient(){}
    public static float now(){return (System.nanoTime()-START)*1e-9f;}
    private static final long START=System.nanoTime();
    public static void install(){ClientboundPartitionPacket.installClientHandler(PartitionClient::receive);net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(PartitionClient::tick);net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut e)->clear());}
    public static void clear(){if(level!=null)PartitionCollision.clear(level);level=null;FIELDS.clear();PENDING.clear();}
    private static void tick(ClientTickEvent.Post event) {
        Level current=Minecraft.getInstance().level;
        if(current!=level){if(level!=null)PartitionCollision.clear(level);FIELDS.clear();level=current;}
        if(level==null)return;
        var pending=List.copyOf(PENDING);PENDING.clear();
        for(var entry:pending){
            if(level.dimension().location().equals(entry.dimension()))receive(entry);else PENDING.add(entry);
        }
        FIELDS.values().removeIf(f->f.stopped>=0&&now()-f.stopped>=.8f);
    }
    public static void receive(ClientboundPartitionPacket p) {
        Level current=Minecraft.getInstance().level;
        if(current==null){PENDING.add(p);return;}
        if(!current.dimension().location().equals(p.dimension())){
            // A removal can arrive while its destination world is still loading. Cancel queued adds too.
            if(p.remove())PENDING.removeIf(queued->queued.dimension().equals(p.dimension())&&queued.id().equals(p.id()));else PENDING.add(p);
            return;
        }
        if(current!=level){if(level!=null)PartitionCollision.clear(level);FIELDS.clear();level=current;}
        var field=FIELDS.get(p.id());
        if(p.remove()) {
            PartitionCollision.remove(level,p.id());if(field!=null&&field.stopped<0)field.stopped=now();
        }else {
            if(field==null)FIELDS.put(p.id(),new Field(p));
            PartitionCollision.Barrier barrier=null;
            if(p.append())for(var b:PartitionCollision.barriers(level))if(b.id.equals(p.id()))barrier=b;
            if(barrier==null)PartitionCollision.put(level,new PartitionCollision.Barrier(p.id(),p.bounds(),p.escaping()));else barrier.escaping.addAll(p.escaping());
        }
    }
    public static List<Field> frames(Vec3 camera,double distance) {
        if(Minecraft.getInstance().level!=level)return List.of();
        return FIELDS.values().stream().filter(f->distanceSquared(f.bounds,camera)<distance*distance)
                .sorted(Comparator.comparingDouble(f->distanceSquared(f.bounds,camera))).toList();
    }
    private static double distanceSquared(PartitionBounds b,Vec3 p) {
        double x=Math.max(b.minX()-p.x,Math.max(0,p.x-b.high(0))),y=Math.max(b.minY()-p.y,Math.max(0,p.y-b.high(1))),z=Math.max(b.minZ()-p.z,Math.max(0,p.z-b.high(2)));
        return x*x+y*y+z*z;
    }
}
