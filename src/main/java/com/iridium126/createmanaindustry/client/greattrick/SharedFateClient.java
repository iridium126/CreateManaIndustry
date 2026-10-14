package com.iridium126.createmanaindustry.client.greattrick;

import com.iridium126.createmanaindustry.compat.trickster.net.ClientboundSharedFatePacket;
import com.iridium126.createmanaindustry.compat.trickster.net.ClientboundSharedFatePacket.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Lifecycle and unique endpoint snapshots only. The GPU owns every intermediate point. */
public final class SharedFateClient {
    private static final long EPOCH=System.nanoTime();
    private static ClientLevel world;
    private static final Map<UUID,Wire> WIRES=new LinkedHashMap<>();
    private static final Map<UUID,Endpoint> POSES=new HashMap<>();
    private static final List<ClientboundSharedFatePacket> PENDING=new ArrayList<>();
    private static final ArrayDeque<Integer> FREE=new ArrayDeque<>();
    private static int nextSlot,generation;
    private SharedFateClient(){}
    public static final class Wire {
        public final UUID id,caster,target;public final int slot,generation,seed;
        public final float born;public final boolean animate;
        public float stopped=-1;
        public Vec3 lastCamera;
        private Pose lastA,lastB;
        Wire(Link link) {
            id=link.id();caster=link.caster();target=link.target();
            slot=FREE.isEmpty()?nextSlot++:FREE.removeFirst();generation=++SharedFateClient.generation;
            seed=id.hashCode()&0xffffff;born=now();animate=link.animate();
        }
    }
    private static final class Endpoint {
        Pose previous,current;float updated;
        Endpoint(Pose pose){previous=current=pose;updated=now();}
        void set(Pose pose){previous=current;current=pose;updated=now();}
        Pose resolve(float partial) {
            var entity=Minecraft.getInstance().level.getEntity(current.entityId());
            if(entity!=null&&entity.getUUID().equals(current.entity()))
                return new Pose(current.entity(),current.entityId(),entity.getPosition(partial),entity.getBbHeight());
            double t=Math.max(0,Math.min(1,(now()-updated)/0.1));
            return new Pose(current.entity(),current.entityId(),previous.position().lerp(current.position(),t),
                    (float)(previous.height()+(current.height()-previous.height())*t));
        }
    }
    public record Frame(Wire wire,Pose a,Pose b) {}
    public static float now(){return (System.nanoTime()-EPOCH)*1e-9f;}
    public static void install(){
        ClientboundSharedFatePacket.installClientHandler(SharedFateClient::receive);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(SharedFateClient::tick);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event)->clear());
    }
    private static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event){
        var level=Minecraft.getInstance().level;if(level==null)return;
        switchWorld(level);drainPending();expireWires();
        var referenced=new HashSet<UUID>();WIRES.values().forEach(w->{referenced.add(w.caster);referenced.add(w.target);});
        POSES.keySet().retainAll(referenced);
    }
    private static void drainPending(){
        if(!PENDING.isEmpty()){var packets=List.copyOf(PENDING);PENDING.clear();packets.forEach(SharedFateClient::receive);}
    }
    private static void expireWires(){
        var expired=WIRES.values().iterator();float time=now();
        while(expired.hasNext()){Wire wire=expired.next();if(wire.stopped>=0&&time-wire.stopped>=0.8f){FREE.addLast(wire.slot);expired.remove();}}
    }
    public static void receive(ClientboundSharedFatePacket packet) {
        var level=Minecraft.getInstance().level;
        if(level==null){PENDING.add(packet);return;}
        switchWorld(level);
        if(!level.dimension().location().equals(packet.dimension()))return;
        for(Pose pose:packet.poses()) {
            Endpoint endpoint=POSES.get(pose.entity());
            if(endpoint==null)POSES.put(pose.entity(),new Endpoint(pose));else endpoint.set(pose);
        }
        for(Link link:packet.links()) {
            Wire existing=WIRES.get(link.id());
            if(link.action()==ClientboundSharedFatePacket.ADD) {
                if(existing!=null&&existing.stopped<0)continue;
                if(existing!=null)FREE.addLast(existing.slot);
                WIRES.put(link.id(),new Wire(link));
            }else if(link.action()==ClientboundSharedFatePacket.REMOVE&&existing!=null&&existing.stopped<0)existing.stopped=now();
        }
    }
    private static void switchWorld(ClientLevel level) {
        if(world==level)return;
        WIRES.clear();POSES.clear();FREE.clear();nextSlot=0;world=level;
    }
    public static List<Frame> frames(Vec3 camera,float partial,double distance,int maxLinks) {
        var level=Minecraft.getInstance().level;
        if(level==null)return List.of();
        switchWorld(level);
        drainPending();expireWires();
        var resolved=new HashMap<UUID,Pose>();var frames=new ArrayList<Frame>();
        for(Wire wire:WIRES.values()) {
            Endpoint ea=POSES.get(wire.caster),eb=POSES.get(wire.target);if(ea==null||eb==null)continue;
            Pose a=wire.stopped>=0?(wire.lastA==null?ea.current:wire.lastA):resolved.computeIfAbsent(wire.caster,id->ea.resolve(partial));
            Pose b=wire.stopped>=0?(wire.lastB==null?eb.current:wire.lastB):resolved.computeIfAbsent(wire.target,id->eb.resolve(partial));
            if(wire.stopped<0){wire.lastA=a;wire.lastB=b;}
            if(distanceSquared(camera,a.position(),b.position())>(distance+27)*(distance+27))continue;
            frames.add(new Frame(wire,a,b));
        }
        frames.sort(Comparator.comparingDouble(f->distanceSquared(camera,f.a.position(),f.b.position())));
        return frames.size()>maxLinks?List.copyOf(frames.subList(0,maxLinks)):frames;
    }
    public static int slots(){return nextSlot;}
    public static void clear(){WIRES.clear();POSES.clear();PENDING.clear();FREE.clear();nextSlot=0;world=null;}
    private static double distanceSquared(Vec3 point,Vec3 a,Vec3 b) {
        var delta=b.subtract(a);double size=delta.lengthSqr(),t=size<1e-12?0:Math.max(0,Math.min(1,point.subtract(a).dot(delta)/size));
        return point.distanceToSqr(a.add(delta.scale(t)));
    }
}
