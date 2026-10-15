package com.iridium126.createmanaindustry.compat.trickster.partition;

import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Common geometry for authoritative server movement and client prediction; no optional mod linkage. */
public final class PartitionCollision {
    public static final class Barrier {
        public final UUID id;
        public final PartitionBounds bounds;
        public final Set<UUID> escaping=new HashSet<>();
        public Barrier(UUID id,PartitionBounds bounds,Collection<UUID> escaping) {this.id=id;this.bounds=bounds;this.escaping.addAll(escaping);}
    }
    private static final Map<Level,Map<UUID,Barrier>> LEVELS=new WeakHashMap<>();
    private static final ThreadLocal<Boolean> TRANSITING=ThreadLocal.withInitial(()->false);
    public interface Transit extends AutoCloseable { void close(); }
    public static boolean transiting(){return TRANSITING.get();}
    public static Transit transit(){boolean previous=TRANSITING.get();TRANSITING.set(true);return ()->{if(previous)TRANSITING.set(true);else TRANSITING.remove();};}
    private PartitionCollision(){}
    public static Collection<Barrier> barriers(Level level) {var map=LEVELS.get(level);return map==null?List.of():map.values();}
    public static void put(Level level,Barrier barrier){LEVELS.computeIfAbsent(level,k->new LinkedHashMap<>()).put(barrier.id,barrier);}
    public static void remove(Level level,UUID id){var map=LEVELS.get(level);if(map!=null)map.remove(id);}
    public static void clear(Level level){LEVELS.remove(level);}
    public static void joined(Entity entity){for(var b:barriers(entity.level()))if(straddles(b.bounds,groupBox(entity)))b.escaping.add(entity.getUUID());}
    public static boolean exempt(Entity e) {
        if(e.noPhysics||e instanceof Player p&&p.isSpectator())return true;
        Entity vehicle=e.getRootVehicle();
        return ServerConfig.partitionMovementExemptEntities.contains(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString())
                ||ServerConfig.partitionMovementExemptEntities.contains(BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType()).toString());
    }
    public static AABB groupBox(Entity entity) {
        Entity root=entity.getRootVehicle();AABB box=root.getBoundingBox();
        for(Entity passenger:root.getIndirectPassengers())box=box.minmax(passenger.getBoundingBox());
        return box;
    }
    private static double[] lo(AABB box){return new double[]{box.minX,box.minY,box.minZ};}
    private static double[] hi(AABB box){return new double[]{box.maxX,box.maxY,box.maxZ};}
    public static boolean straddles(PartitionBounds b,AABB box){return b.straddles(lo(box),hi(box));}
    private static boolean escaping(Barrier b,Entity entity,AABB box) {
        UUID id=entity.getRootVehicle().getUUID();
        boolean was=b.escaping.contains(id)||b.escaping.contains(entity.getUUID());
        if(was&&!straddles(b.bounds,box)){b.escaping.remove(id);b.escaping.remove(entity.getUUID());return false;}
        return was;
    }
    public static Vec3 clip(Entity entity,Vec3 move,AABB box) {
        if(entity==null||exempt(entity)||barriers(entity.level()).isEmpty())return move;
        AABB group=groupBox(entity);
        // Vanilla's step-up probes pass shifted boxes; preserve their offset.
        group=group.move(box.minX-entity.getBoundingBox().minX,box.minY-entity.getBoundingBox().minY,box.minZ-entity.getBoundingBox().minZ);
        double[] low=lo(group),high=hi(group),delta={move.x,move.y,move.z},result=new double[3];
        var active=barriers(entity.level()).stream().filter(b->!escaping(b,entity,groupBox(entity))).toList();
        // Contact first, then slide in the remaining axes. At most three independent normals can block.
        for(int step=0;step<3;step++) {
            double time=1;int normal=-1;
            for(var b:active)for(int axis=0;axis<3;axis++){double t=b.bounds.contact(axis,delta,low,high);if(t<=time){time=t;normal=axis;}}
            for(int axis=0;axis<3;axis++){double travel=delta[axis]*time;result[axis]+=travel;low[axis]+=travel;high[axis]+=travel;delta[axis]*=1-time;}
            if(normal<0)break;delta[normal]=0;
        }
        return new Vec3(result[0],result[1],result[2]);
    }
    public static boolean teleportAllowed(Entity e,Vec3 destination) {
        if(exempt(e))return true;
        Vec3 from=e.position();AABB box=groupBox(e);
        for(var b:barriers(e.level()))if(!escaping(b,e,box)&&b.bounds.crosses(from.x,from.y,from.z,destination.x,destination.y,destination.z))return false;
        return true;
    }
}
