package com.iridium126.createmanaindustry.compat.trickster.partition;

import at.petrak.hexcasting.forge.xplat.ForgeXplatImpl;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.ExecutionScope;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.HexJitRuntime;
import com.iridium126.createmanaindustry.compat.hextrick.execution.CastingContexts;
import com.iridium126.createmanaindustry.compat.hextrick.execution.CircleSourceDescriptor;
import com.iridium126.createmanaindustry.compat.trickster.anima.BatteryAnima;
import com.iridium126.createmanaindustry.compat.trickster.casting.*;
import com.iridium126.createmanaindustry.compat.trickster.net.ClientboundPartitionPacket;
import com.iridium126.createmanaindustry.compat.trickster.sharedfate.SharedFateManager;
import java.util.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Main-thread authority. Sorted X bounds index membership/conflict queries without block enumeration. */
public final class PartitionManager {
    record Domain(UUID id,CastChain chain,ServerLevel level,PartitionCollision.Barrier barrier,long born) {
    }
    private static final PartitionIndex INDEX = new PartitionIndex();
    private static final Map<UUID,Domain> CHAINS=new HashMap<>();
    private static final Map<ServerPlayer,Map<UUID,ClientboundPartitionPacket>> KNOWN=new WeakHashMap<>();
    private static final ThreadLocal<UUID> IGNORED=new ThreadLocal<>();
    private static final ThreadLocal<Level> CAST_WORLD=new ThreadLocal<>();
    public static boolean currentWorldActive() {
        return active(CAST_WORLD.get());
    }
    public static CastChains.Scope enterWorld(Level level) {
        Level previous=CAST_WORLD.get();
        CAST_WORLD.set(level);
        return ()-> {
            if(previous==null)CAST_WORLD.remove();
            else CAST_WORLD.set(previous);
        };
    }
    private PartitionManager() {
    }
    public static boolean active(Level level) {
        return INDEX.active(level);
    }
    public static boolean anyActive() {
        return !CHAINS.isEmpty();
    }
    public static boolean crosses(Level level,Vec3 from,Vec3 to) {
        double high=Math.max(from.x,to.x);
        for(var list:INDEX.before(level,high))for(var d:list) {
            if(d.chain.id().equals(IGNORED.get()))continue;
            var b=d.barrier.bounds;
            if(b.crosses(from.x,from.y,from.z,to.x,to.y,to.z))return true;
        }
        return false;
    }
    public static boolean valid(ServerLevel level,PartitionBounds b) {
        try {
            b.cost();
            return b.minX()>=-29999984&&b.maxX()<29999984&&b.minZ()>=-29999984&&b.maxZ()<29999984
            &&b.minY()>=level.getMinBuildHeight()&&b.maxY()<level.getMaxBuildHeight()
            &&level.getWorldBorder().isWithinBounds(b.minX(),b.minZ())&&level.getWorldBorder().isWithinBounds(b.high(0),b.high(2));
        }catch(ArithmeticException e) {
            return false;
        }
    }
    public static boolean conflict(ServerLevel level,PartitionBounds bounds,UUID chain) {
        for(var list:INDEX.before(level,bounds.high(0)))for(var d:list)
        if(!d.chain.id().equals(chain)&&bounds.conflicts(d.barrier.bounds))return true;
        return false;
    }
    public static boolean create(ServerPlayer player,CastChain chain,PartitionBounds bounds) {
        return create(player,chain,bounds,new Vec3(bounds.minX()+.5,bounds.minY()+.5,bounds.minZ()+.5),new Vec3(bounds.maxX()+.5,bounds.maxY()+.5,bounds.maxZ()+.5));
    }
    public static boolean create(ServerPlayer player,CastChain chain,PartitionBounds bounds,Vec3 first,Vec3 second) {
        var origin=CastingContexts.origin();
        ServerLevel level=CastingContexts.world(player,origin);
        if(level==null||origin!=null&&origin.source().resolve(player.getServer()).status()!=CircleSourceDescriptor.Status.READY)return false;
        if(!valid(level,bounds)||conflict(level,bounds,chain.id()))return false;
        UUID previous=IGNORED.get();
        IGNORED.set(chain.id());
        try(var ignored=CastChains.enter(chain)) {
            var range=CastingContexts.environment(player,origin);
                if(!range.isVecInRange(first)
            ||!range.isVecInRange(second))return false;
        }finally {
            if(previous==null)IGNORED.remove();
            else IGNORED.set(previous);
        }
        var receipt=BatteryAnima.reserve(player,bounds.cost());
        if(receipt.isEmpty())return false;
        try(var payment=receipt.get()) {
            SavedPartition saved=new SavedPartition(level.dimension().location(),bounds);
            chain.partitionOrigin(origin);
            install(level,chain,saved);
            payment.commit();
            changed(level.getServer());
            return true;
        }
    }
    private static void install(ServerLevel level,CastChain chain,SavedPartition saved) {
        UUID id=UUID.randomUUID();
        var barrier=new PartitionCollision.Barrier(id,saved.bounds(),List.of());
        for(var entity:level.getAllEntities())if(PartitionCollision.straddles(saved.bounds(),PartitionCollision.groupBox(entity)))barrier.escaping.add(entity.getUUID());
        // Prepare everything before swapping the old entry, then publish without invoking user code.
        remove(chain,false);
        chain.partition(saved);
        var d=new Domain(id,chain,level,barrier,level.getGameTime());
        CHAINS.put(chain.id(),d);
        INDEX.add(d);
        PartitionCollision.put(level,barrier);
        persistStaff(level.getServer(),chain);
    }
    public static void resume(CastChain chain,ServerPlayer player) {
        if(chain.partition()==null||CHAINS.containsKey(chain.id()))return;
        if(chain.partitionOrigin()!=null) {
            var status=chain.partitionOrigin().source().resolve(player.getServer()).status();
            if(status==CircleSourceDescriptor.Status.WAITING)return;
            if(status==CircleSourceDescriptor.Status.INVALID) {
                chain.partition(null);
                chain.partitionOrigin(null);
                return;
            }
        }
        SavedPartition saved=chain.partition();
        ServerLevel level=player.getServer().getLevel(ResourceKey.create(Registries.DIMENSION,saved.dimension()));
        if(level==null||!valid(level,saved.bounds())||conflict(level,saved.bounds(),chain.id())) {
            chain.partition(null);
            persistStaff(player.getServer(),chain);
            player.sendSystemMessage(Component.translatable("createmanaindustry.partition.restore_failed"));
            return;
        }
        install(level,chain,saved);
        changed(player.getServer());
    }
    public static void suspend(CastChain chain) {
        Domain d=remove(chain,false);
        if(d!=null)changed(d.level.getServer());
    }
    public static void end(CastChain chain) {
        Domain d=remove(chain,true);
        if(d!=null)changed(d.level.getServer());
    }
    private static Domain remove(CastChain chain,boolean forget) {
        Domain old=CHAINS.remove(chain.id());
        if(forget) {
            chain.partition(null);
            chain.partitionOrigin(null);
        }
        if(old!=null) {
            INDEX.remove(old);
            PartitionCollision.remove(old.level,old.id);
            if(forget)persistStaff(old.level.getServer(),chain);
        }
        return old;
    }
    private static void persistStaff(MinecraftServer server,CastChain chain) {
        if(chain.player()==null)return;
        var player=server.getPlayerList().getPlayer(chain.player());
        if(player==null)return;
        var data=player.getPersistentData().getCompound(ForgeXplatImpl.TAG_VM).getCompound("userData");
        String encoded=data.getString("cmi_cast_chain");
        if(encoded.isEmpty()||!CastChain.decode(encoded).id().equals(chain.id()))return;
        if(chain.partition()==null)data.remove("cmi_partition");
        else data.putString("cmi_partition",chain.partition().encode());
        data.putString("cmi_effect_origins",chain.encodeOrigins());
    }
    private static void changed(MinecraftServer server) {
        ExecutionScope.resourceTransition();
        HexJitRuntime.invalidate("partition changed");
        SharedFateManager.refresh(server);
        sync(server);
    }
    private static void sync(MinecraftServer server) {
        PartitionSynchronizer.sync(server, CHAINS.values());
    }
    public static void register() {
        NeoForge.EVENT_BUS.addListener(PartitionManager::joined);
        NeoForge.EVENT_BUS.addListener(PartitionManager::tick);
        NeoForge.EVENT_BUS.addListener(PartitionManager::stopped);
    }

    private static void joined(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide()) PartitionCollision.joined(event.getEntity());
    }

    private static void tick(ServerTickEvent.Post event) {
        for (Domain domain : List.copyOf(CHAINS.values())) {
            if (domain.level.getServer() != event.getServer()) continue;
            if (!domain.chain.live()) {
                end(domain.chain);
                continue;
            }
            if (domain.chain.partitionOrigin() != null && domain.chain.partitionOrigin().source().resolve(event.getServer()).status()
                    == CircleSourceDescriptor.Status.INVALID) {
                end(domain.chain);
                continue;
            }
            domain.barrier.escaping.removeIf(id -> {
                var entity = domain.level.getEntity(id);
                return entity == null || !PartitionCollision.straddles(domain.barrier.bounds, PartitionCollision.groupBox(entity));
            });
        }
        sync(event.getServer());
    }

    private static void stopped(ServerStoppedEvent event) {
        INDEX.clear(event.getServer());
        CHAINS.values().removeIf(domain -> domain.level.getServer() == event.getServer());
        PartitionSynchronizer.stopped(event.getServer());
    }
}
