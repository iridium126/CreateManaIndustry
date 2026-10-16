package com.iridium126.createmanaindustry.compat.trickster.partition;

import at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv;
import com.iridium126.createmanaindustry.compat.trickster.anima.BatteryAnima;
import com.iridium126.createmanaindustry.compat.trickster.casting.*;
import com.iridium126.createmanaindustry.compat.trickster.net.ClientboundPartitionPacket;
import com.iridium126.createmanaindustry.compat.trickster.sharedfate.SharedFateManager;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.HexJitRuntime;
import net.minecraft.server.level.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

/** Main-thread authority. Sorted X bounds index membership/conflict queries without block enumeration. */
public final class PartitionManager {
    private record Domain(UUID id,CastChain chain,ServerLevel level,PartitionCollision.Barrier barrier,long born){}
    private static final Map<ServerLevel,NavigableMap<Integer,List<Domain>>> INDEX=new IdentityHashMap<>();
    private static final Map<UUID,Domain> CHAINS=new HashMap<>();
    private static final Map<ServerPlayer,Map<UUID,ClientboundPartitionPacket>> KNOWN=new WeakHashMap<>();
    private static final ThreadLocal<UUID> IGNORED=new ThreadLocal<>();
    private static final ThreadLocal<Level> CAST_WORLD=new ThreadLocal<>();
    public static boolean currentWorldActive(){return active(CAST_WORLD.get());}
    public static CastChains.Scope enterWorld(Level level){Level previous=CAST_WORLD.get();CAST_WORLD.set(level);return ()->{if(previous==null)CAST_WORLD.remove();else CAST_WORLD.set(previous);};}
    private PartitionManager(){}
    public static boolean active(Level level){var index=INDEX.get(level);return index!=null&&!index.isEmpty();}
    public static boolean anyActive(){return !CHAINS.isEmpty();}
    public static boolean crosses(Level level,Vec3 from,Vec3 to) {
        var index=INDEX.get(level);if(index==null)return false;
        double high=Math.max(from.x,to.x);
        for(var list:index.headMap(high>=Integer.MAX_VALUE?Integer.MAX_VALUE:(int)Math.floor(high),true).values())for(var d:list) {
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
        }catch(ArithmeticException e){return false;}
    }
    public static boolean conflict(ServerLevel level,PartitionBounds bounds,UUID chain) {
        var index=INDEX.get(level);if(index==null)return false;
        for(var list:index.headMap((int)Math.min(Integer.MAX_VALUE,bounds.high(0)),true).values())for(var d:list)
            if(!d.chain.id().equals(chain)&&bounds.conflicts(d.barrier.bounds))return true;
        return false;
    }
    public static boolean create(ServerPlayer player,CastChain chain,PartitionBounds bounds) {
        return create(player,chain,bounds,new Vec3(bounds.minX()+.5,bounds.minY()+.5,bounds.minZ()+.5),new Vec3(bounds.maxX()+.5,bounds.maxY()+.5,bounds.maxZ()+.5));
    }
    public static boolean create(ServerPlayer player,CastChain chain,PartitionBounds bounds,Vec3 first,Vec3 second) {
        ServerLevel level=player.serverLevel();
        if(!valid(level,bounds)||conflict(level,bounds,chain.id()))return false;
        UUID previous=IGNORED.get();IGNORED.set(chain.id());
        try(var ignored=CastChains.enter(chain)) {
            StaffCastEnv range=new StaffCastEnv(player,InteractionHand.MAIN_HAND);
            if(!range.isVecInRange(first)
                    ||!range.isVecInRange(second))return false;
        }finally{if(previous==null)IGNORED.remove();else IGNORED.set(previous);}
        var receipt=BatteryAnima.reserve(player,bounds.cost());if(receipt.isEmpty())return false;
        try(var payment=receipt.get()) {
            SavedPartition saved=new SavedPartition(level.dimension().location(),bounds);
            install(level,chain,saved);payment.commit();changed(level.getServer());return true;
        }
    }
    private static void install(ServerLevel level,CastChain chain,SavedPartition saved) {
        UUID id=UUID.randomUUID();var barrier=new PartitionCollision.Barrier(id,saved.bounds(),List.of());
        for(var entity:level.getAllEntities())if(PartitionCollision.straddles(saved.bounds(),PartitionCollision.groupBox(entity)))barrier.escaping.add(entity.getUUID());
        // Prepare everything before swapping the old entry, then publish without invoking user code.
        remove(chain,false);chain.partition(saved);
        var d=new Domain(id,chain,level,barrier,level.getGameTime());CHAINS.put(chain.id(),d);
        INDEX.computeIfAbsent(level,k->new TreeMap<>()).computeIfAbsent(saved.bounds().minX(),k->new ArrayList<>()).add(d);
        PartitionCollision.put(level,barrier);
        persistStaff(level.getServer(),chain);
    }
    public static void resume(CastChain chain,ServerPlayer player) {
        if(chain.partition()==null||CHAINS.containsKey(chain.id()))return;
        SavedPartition saved=chain.partition();ServerLevel level=player.getServer().getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,saved.dimension()));
        if(level==null||!valid(level,saved.bounds())||conflict(level,saved.bounds(),chain.id())) {
            chain.partition(null);persistStaff(player.getServer(),chain);player.sendSystemMessage(Component.translatable("createmanaindustry.partition.restore_failed"));return;
        }
        install(level,chain,saved);changed(player.getServer());
    }
    public static void suspend(CastChain chain){Domain d=remove(chain,false);if(d!=null)changed(d.level.getServer());}
    public static void end(CastChain chain){Domain d=remove(chain,true);if(d!=null)changed(d.level.getServer());}
    private static Domain remove(CastChain chain,boolean forget) {
        Domain old=CHAINS.remove(chain.id());if(forget)chain.partition(null);
        if(old!=null){var index=INDEX.get(old.level);var list=index.get(old.barrier.bounds.minX());list.remove(old);if(list.isEmpty())index.remove(old.barrier.bounds.minX());PartitionCollision.remove(old.level,old.id);if(forget)persistStaff(old.level.getServer(),chain);}
        return old;
    }
    private static void persistStaff(MinecraftServer server,CastChain chain) {
        if(chain.player()==null)return;var player=server.getPlayerList().getPlayer(chain.player());if(player==null)return;
        var data=player.getPersistentData().getCompound(at.petrak.hexcasting.forge.xplat.ForgeXplatImpl.TAG_VM).getCompound("userData");
        String encoded=data.getString("cmi_cast_chain");if(encoded.isEmpty()||!CastChain.decode(encoded).id().equals(chain.id()))return;
        if(chain.partition()==null)data.remove("cmi_partition");else data.putString("cmi_partition",chain.partition().encode());
    }
    private static void changed(MinecraftServer server) {com.iridium126.createmanaindustry.compat.hexcasting.jit.ExecutionScope.resourceTransition();HexJitRuntime.invalidate("partition changed");SharedFateManager.refresh(server);sync(server);}
    private static void sync(MinecraftServer server) {
        for(ServerPlayer player:server.getPlayerList().getPlayers()) {
            var known=KNOWN.computeIfAbsent(player,k->new HashMap<>());var desired=new HashMap<UUID,ClientboundPartitionPacket>();
            // Logic metadata must cover all domains: distant teleport prediction also needs membership.
            for(Domain d:CHAINS.values())if(d.level==player.serverLevel()) {
                var packet=new ClientboundPartitionPacket(d.level.dimension().location(),d.id,d.barrier.bounds,false,d.born==d.level.getGameTime(),List.copyOf(d.barrier.escaping));
                desired.put(d.id,packet);var old=known.get(d.id);
                if(old==null||!old.escaping().equals(packet.escaping()))send(player,packet);
            }
            for(var old:known.values())if(!desired.containsKey(old.id()))send(player,new ClientboundPartitionPacket(old.dimension(),old.id(),old.bounds(),true,false,List.of()));
            known.clear();known.putAll(desired);
        }
    }
    private static void send(ServerPlayer player,ClientboundPartitionPacket packet) {
        for(int i=0;i<Math.max(1,packet.escaping().size());i+=1024)PacketDistributor.sendToPlayer(player,new ClientboundPartitionPacket(packet.dimension(),packet.id(),packet.bounds(),packet.remove(),packet.animate(),packet.escaping().subList(i,Math.min(i+1024,packet.escaping().size())),i>0));
    }
    public static void register() {
        NeoForge.EVENT_BUS.addListener((EntityJoinLevelEvent e)->{if(!e.getLevel().isClientSide())PartitionCollision.joined(e.getEntity());});
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e)-> {
            for(Domain d:List.copyOf(CHAINS.values()))if(d.level.getServer()==e.getServer()) {
                if(!d.chain.live()){end(d.chain);continue;}
                d.barrier.escaping.removeIf(id->{var entity=d.level.getEntity(id);return entity==null||!PartitionCollision.straddles(d.barrier.bounds,PartitionCollision.groupBox(entity));});
            }
            sync(e.getServer());
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e)-> {
            INDEX.keySet().removeIf(level->{if(level.getServer()!=e.getServer())return false;PartitionCollision.clear(level);return true;});
            CHAINS.values().removeIf(d->d.level.getServer()==e.getServer());KNOWN.keySet().removeIf(p->p.getServer()==e.getServer());
        });
    }
}
