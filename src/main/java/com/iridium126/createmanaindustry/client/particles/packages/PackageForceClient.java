package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.*;
import java.util.concurrent.*;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageForceHooks;
import com.simibubi.create.content.kinetics.fan.AirCurrent;
import com.simibubi.create.content.logistics.box.PackageEntity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.scores.Team;
import net.neoforged.fml.ModList;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/** Tick-owned mutable world reads, immutable worker input, zero additional force network packets.
 * Native entity and fan synchronization supplies sources; the GPU calculates all package impulses. */
@EventBusSubscriber(modid=CreateManaIndustry.MODID,value=Dist.CLIENT)
public final class PackageForceClient implements AutoCloseable {
    public static final PackageForceHooks.Listener HOOKS=new PackageForceHooks.Listener(){
        @Override public void fan(AirCurrent current){captureFan(current);}
        @Override public boolean owned(PackageEntity entity){return PackageRenderOwnership.authorityOwned(entity);}
        @Override public boolean active(){return PackageAuthorityClient.activePackages()>0;}
    };
    private record Fan(long tick,PackageForceScene.Source source,String error) {}
    private static final class Sources {
        final Set<Entity> entities=new LinkedHashSet<>();
        final Map<AirCurrent,Fan> fans=new LinkedHashMap<>();
        OptionalBridge bridge;boolean bridgeResolved;
    }
    private static final Map<ClientLevel,Sources> worlds=new IdentityHashMap<>();
    interface OptionalBridge {PackageForceScene.Source entity(PackageForceScene.Source source,Entity entity);PackageForceScene.Source fan(PackageForceScene.Source source,AirCurrent fan);}
    static OptionalBridge optionalBridge(boolean loaded){return loaded?new PackageSableForceSources():null;}
    private static OptionalBridge bridge(Sources sources){if(!sources.bridgeResolved){sources.bridge=optionalBridge(ModList.get().isLoaded("sable"));sources.bridgeResolved=true;}return sources.bridge;}
    private final ClientLevel level;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"CMI package force BVH");t.setDaemon(true);return t;});
    private CompletableFuture<PackageForceScene.Snapshot> pending;
    private PackageForceScene.Snapshot snapshot;
    private long scheduled=Long.MIN_VALUE;
    private boolean closed;
    public PackageForceClient(ClientLevel level){this.level=java.util.Objects.requireNonNull(level);}
    @SubscribeEvent public static void joined(EntityJoinLevelEvent e){if(e.getLevel() instanceof ClientLevel level&&!(e.getEntity() instanceof PackageEntity))worlds.computeIfAbsent(level,k->new Sources()).entities.add(e.getEntity());}
    @SubscribeEvent public static void left(EntityLeaveLevelEvent e){if(e.getLevel() instanceof ClientLevel level){var sources=worlds.get(level);if(sources!=null)sources.entities.remove(e.getEntity());}}
    @SubscribeEvent public static void unloaded(LevelEvent.Unload e){if(e.getLevel() instanceof ClientLevel level)worlds.remove(level);}
    private static void captureFan(AirCurrent current){
        if(!com.iridium126.createmanaindustry.infrastructure.config.ClientConfig.packageGpuAuthority)return;
        if(!(current.source.getAirCurrentWorld() instanceof ClientLevel level))return;
        var sources=worlds.computeIfAbsent(level,k->new Sources());
        if(current.source.isSourceRemoved()||current.direction==null||current.maxDistance<=0||current.source.getSpeed()==0){sources.fans.remove(current);return;}
        var bounds=current.bounds;if(bounds.getSize()==0){sources.fans.remove(current);return;}
        var p=current.source.getAirCurrentPos();var flow=(current.pushing?current.direction:current.direction.getOpposite()).getNormal();
        try{var source=new PackageForceScene.Source(PackageForceScene.FAN,bounds.minX,bounds.minY,bounds.minZ,bounds.maxX,bounds.maxY,bounds.maxZ,
                p.getX()+.5,p.getY()+.5,p.getZ()+.5,Math.abs(current.source.getSpeed())/512f,flow.getX(),flow.getY(),flow.getZ(),current.maxDistance);
            var bridge=bridge(sources);if(bridge!=null)source=bridge.fan(source,current);
            sources.fans.remove(current);sources.fans.put(current,new Fan(level.getGameTime(),source,null));
        }catch(RuntimeException|LinkageError failure){sources.fans.put(current,new Fan(level.getGameTime(),null,"Sable fan force capture unavailable: "+failure.getMessage()));}
    }
    /** Called once per tick from the client world runtime, never from a worker. No package scan. */
    public void prepare(double ox,double oy,double oz){
        if(closed)throw new IllegalStateException("Package force client closed");
        if(pending!=null&&pending.isDone()){snapshot=pending.join();pending=null;}
        long tick=level.getGameTime();if(pending!=null||scheduled==tick)return;
        var captured=new ArrayList<PackageForceScene.Source>();var sources=worlds.get(level);
        if(sources!=null){var bridge=bridge(sources);for(Entity e:sources.entities){
                if(!e.isAlive()||e.isRemoved()||e.noPhysics||e.isSpectator()||!e.isPushable()||e instanceof LivingEntity living&&living.isSleeping())continue;
                var team=e.getTeam();if(team!=null&&(team.getCollisionRule()==Team.CollisionRule.NEVER||team.getCollisionRule()==Team.CollisionRule.PUSH_OTHER_TEAMS))continue;
                var b=e.getBoundingBox();if(b.getSize()<=0)continue;
                var source=new PackageForceScene.Source(PackageForceScene.ENTITY,b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ,
                        e.getX(),e.getY(),e.getZ(),e instanceof LivingEntity?1:0,0,0,0,0);
                captured.add(bridge==null?source:bridge.entity(source,e));
            }
            sources.fans.entrySet().removeIf(row->row.getKey().source.isSourceRemoved()||tick-row.getValue().tick>1);
            for(var fan:sources.fans.values()){if(fan.error!=null)throw new IllegalStateException(fan.error);captured.add(fan.source);}
        }
        if(captured.size()>PackageForceScene.MAX_SOURCES)throw new IllegalStateException("Package force source capacity; restoring Create");
        var immutable=List.copyOf(captured);scheduled=tick;
        pending=CompletableFuture.supplyAsync(()->PackageForceScene.bake(tick,immutable,ox,oy,oz),worker);
    }
    public boolean ready(){return !closed&&snapshot!=null&&level.getGameTime()>=snapshot.tick()&&level.getGameTime()-snapshot.tick()<=1;}
    public PackageForceScene.Snapshot snapshot(){if(!ready())throw new IllegalStateException("Package force capture overdue; restoring Create");return snapshot;}
    @Override public void close(){if(closed)return;closed=true;if(pending!=null)pending.cancel(false);pending=null;snapshot=null;worker.shutdownNow();}
}
