package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.*;
import java.util.concurrent.*;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageForceHooks;
import com.simibubi.create.content.kinetics.fan.AirCurrent;
import com.simibubi.create.content.kinetics.fan.NozzleBlockEntity;
import com.simibubi.create.content.logistics.box.PackageEntity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.scores.Team;
import net.neoforged.fml.ModList;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageRegion;

/** Tick-owned mutable world reads, immutable worker input, zero additional force network packets.
 * Native entity, fan, and nozzle state supplies sources; the GPU calculates package impulses. */
@EventBusSubscriber(modid=CreateManaIndustry.MODID,value=Dist.CLIENT)
public final class PackageForceClient implements AutoCloseable {
    public static final PackageForceHooks.Listener HOOKS=new PackageForceHooks.Listener(){
        @Override public void fan(AirCurrent current){captureFan(current);}
        @Override public void nozzle(NozzleBlockEntity nozzle,float range,boolean pushing){captureNozzle(nozzle,range,pushing);}
    };
    private record Flow(long tick,PackageForceScene.Source source) {}
    private static final double QUERY_MARGIN=2.0;
    private static final class Sources {
        final Map<Object,Flow> airflow=new LinkedHashMap<>();
        List<PackageRegion> regions=List.of();
        OptionalBridge bridge;boolean bridgeResolved;
    }
    private static final Map<ClientLevel,Sources> worlds=new IdentityHashMap<>();
    interface OptionalBridge {PackageForceScene.Source entity(PackageForceScene.Source source,Entity entity);PackageForceScene.Source fan(PackageForceScene.Source source,AirCurrent fan);PackageForceScene.Source nozzle(PackageForceScene.Source source,NozzleBlockEntity nozzle);}
    static OptionalBridge optionalBridge(boolean loaded){return loaded?new PackageSableForceSources():null;}
    private static OptionalBridge bridge(Sources sources){if(!sources.bridgeResolved){sources.bridge=optionalBridge(ModList.get().isLoaded("sable"));sources.bridgeResolved=true;}return sources.bridge;}
    private final ClientLevel level;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"CMI package force BVH");t.setDaemon(true);return t;});
    private final ArrayList<PackageForceScene.Source> captured=new ArrayList<>();
    private final Set<Entity> visited=Collections.newSetFromMap(new IdentityHashMap<>());
    private final PackageForceSnapshots snapshots=new PackageForceSnapshots();
    private List<PackageRegion> captureRegions=List.of();
    private boolean closed;
    public PackageForceClient(ClientLevel level){this.level=java.util.Objects.requireNonNull(level);}
    @SubscribeEvent public static void unloaded(LevelEvent.Unload e){if(e.getLevel() instanceof ClientLevel level)worlds.remove(level);}
    private static void captureFan(AirCurrent current){
        if(!com.iridium126.createmanaindustry.infrastructure.config.ClientConfig.packageGpuAuthority)return;
        ClientLevel level;
        try{if(!(current.source.getAirCurrentWorld() instanceof ClientLevel clientLevel)){removeFan(current);return;}level=clientLevel;}
        catch(RuntimeException|LinkageError unavailable){removeFan(current);return;}
        var sources=worlds.computeIfAbsent(level,k->new Sources());
        var fan=PackageForceCapturePolicy.optionalSource(()->{
            if(current.source.isSourceRemoved()||current.direction==null||current.maxDistance<=0||current.source.getSpeed()==0)return null;
            var bounds=current.bounds;if(bounds.getSize()==0)return null;
            var p=current.source.getAirCurrentPos();var flow=(current.pushing?current.direction:current.direction.getOpposite()).getNormal();
            var source=new PackageForceScene.Source(PackageForceScene.FAN,bounds.minX,bounds.minY,bounds.minZ,bounds.maxX,bounds.maxY,bounds.maxZ,
                    p.getX()+.5,p.getY()+.5,p.getZ()+.5,Math.abs(current.source.getSpeed())/512f,flow.getX(),flow.getY(),flow.getZ(),current.maxDistance);
            var optional=bridge(sources);if(optional!=null)source=optional.fan(source,current);
            return PackageForceScene.intersectsRegions(source,sources.regions,QUERY_MARGIN)?source:null;
        });
        // A stale Sable sub-level pose only invalidates this fan for this capture. Never put a
        // failed optional source in the shared history: that used to fail prepare() and revoke
        // GPU authority for every package in the world.
        if(fan==null)sources.airflow.remove(current);else {sources.airflow.remove(current);sources.airflow.put(current,new Flow(level.getGameTime(),fan));}
    }
    private static void captureNozzle(NozzleBlockEntity nozzle,float range,boolean pushing){
        if(!com.iridium126.createmanaindustry.infrastructure.config.ClientConfig.packageGpuAuthority){removeNozzle(nozzle);return;}
        if(!(nozzle.getLevel() instanceof ClientLevel level)){removeNozzle(nozzle);return;}
        var sources=worlds.computeIfAbsent(level,k->new Sources());
        var captured=PackageForceCapturePolicy.optionalSource(()->{
            if(nozzle.isRemoved()||!(range>0)||!Float.isFinite(range))return null;
            var p=nozzle.getBlockPos();double x=p.getX()+.5,y=p.getY()+.5,z=p.getZ()+.5,half=range*.5;
            var source=new PackageForceScene.Source(PackageForceScene.NOZZLE,x-half,y-half,z-half,x+half,y+half,z+half,
                    x,y,z,1/32f,pushing?1:-1,0,0,range);
            var optional=bridge(sources);if(optional!=null)source=optional.nozzle(source,nozzle);
            return PackageForceScene.intersectsRegions(source,sources.regions,QUERY_MARGIN)?source:null;
        });
        if(captured==null)sources.airflow.remove(nozzle);else {sources.airflow.remove(nozzle);sources.airflow.put(nozzle,new Flow(level.getGameTime(),captured));}
    }
    private static void removeFan(AirCurrent current){for(var sources:worlds.values())sources.airflow.remove(current);}
    private static void removeNozzle(NozzleBlockEntity nozzle){for(var sources:worlds.values())sources.airflow.remove(nozzle);}
    /** Called once per tick from the client world runtime, never from a worker. No package scan. */
    public void prepare(double ox,double oy,double oz,Collection<PackageRegion> activeRegions){
        if(closed)throw new IllegalStateException("Package force client closed");
        long worldTick=level.getGameTime();var input=PackageClientInputs.current(level);long tick=input.last();
        snapshots.tickRate(level.tickRateManager().tickrate());
        var regions=List.copyOf(activeRegions);var sources=worlds.computeIfAbsent(level,k->new Sources());
        if(!sources.regions.equals(regions))sources.regions=regions;
        if(!captureRegions.equals(regions)){captureRegions=regions;snapshots.changedRegions(tick);}
        if(!snapshots.needsCapture(tick))return;
        sources.airflow.entrySet().removeIf(row->row.getKey() instanceof AirCurrent fan&&fan.source.isSourceRemoved()
                ||row.getKey() instanceof NozzleBlockEntity nozzle&&nozzle.isRemoved()
                ||worldTick-row.getValue().tick>1
                ||!PackageForceScene.intersectsRegions(row.getValue().source,regions,QUERY_MARGIN));
        captured.clear();visited.clear();var bridge=bridge(sources);
        for(var region:regions) {
            double x=region.originX(),y=region.originY(),z=region.originZ();
            var area=new AABB(x-QUERY_MARGIN,y-QUERY_MARGIN,z-QUERY_MARGIN,
                    x+PackageRegion.SIZE+QUERY_MARGIN,y+PackageRegion.SIZE+QUERY_MARGIN,z+PackageRegion.SIZE+QUERY_MARGIN);
            for(Entity e:level.getEntities((Entity)null,area,PackageForceClient::eligibleEntity)) {
                if(!visited.add(e))continue;
                var framed=PackageForceCapturePolicy.optionalSource(()->{
                    var b=e.getBoundingBox();
                    var source=new PackageForceScene.Source(PackageForceScene.ENTITY,b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ,
                            e.getX(),e.getY(),e.getZ(),e instanceof LivingEntity?1:0,0,0,0,0);
                    return bridge==null?source:bridge.entity(source,e);
                });
                if(framed!=null)captured.add(framed);
            }
        }
        for(var source:sources.airflow.values())captured.add(source.source);
        if(captured.size()>PackageForceScene.MAX_SOURCES)throw new IllegalStateException("Package force source capacity; restoring Create");
        snapshots.captureRange(input.first(),tick,captured,ox,oy,oz,worker);
    }
    private static boolean eligibleEntity(Entity e) {
        if(e instanceof PackageEntity||!e.isAlive()||e.isRemoved()||e.noPhysics||e.isSpectator()||!e.isPushable()
                ||e instanceof LivingEntity living&&living.isSleeping())return false;
        var team=e.getTeam();
        if(team!=null&&(team.getCollisionRule()==Team.CollisionRule.NEVER||team.getCollisionRule()==Team.CollisionRule.PUSH_OTHER_TEAMS))return false;
        return e.getBoundingBox().getSize()>0;
    }
    public boolean ready(long tick){return !closed&&snapshots.ready(tick);}
    public boolean contains(long tick){return !closed&&snapshots.contains(tick);}
    public PackageForceScene.Snapshot snapshot(long tick){return snapshots.snapshot(tick);}
    public Object snapshotIdentity(long tick){return snapshots.identity(tick);}
    public void consumed(long tick){snapshots.consumed(tick);}
    public boolean ready(){return !closed&&snapshots.admissionReady(PackageClientInputs.current(level).last());}
    public PackageForceScene.Snapshot snapshot(){if(closed)throw new IllegalStateException("Package force client closed");return snapshots.snapshot(PackageClientInputs.current(level).last());}
    @Override public void close(){if(closed)return;closed=true;snapshots.clear();worker.shutdownNow();}
}
