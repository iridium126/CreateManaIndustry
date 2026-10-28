package com.iridium126.createmanaindustry.content.allaystorm.lod;

import java.util.*;
import com.iridium126.createmanaindustry.CMIAttachments;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.content.allaystorm.*;
import com.iridium126.createmanaindustry.content.allaystorm.network.*;
import com.iridium126.createmanaindustry.content.allaystorm.rift.StormVisualDefinition;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import net.minecraft.server.level.*;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Independent visual-only observers. Never creates a full track or authority. */
@EventBusSubscriber(modid=CreateManaIndustry.MODID)
public final class StormLodServer {
    private static long nextSubscription;
    private record Observer(int limit,long subscription) {}
    private static final Map<UUID,Integer> PREFERENCES=new HashMap<>();
    private static final Map<ServerLevel,State> LEVELS=new HashMap<>();
    private static final class State {
        final StormCenterTimeline centers=new StormCenterTimeline();
        final Map<UUID,Observer> recipients=new HashMap<>();
        final BitSet deaths=new BitSet();
        StormIdentity identity;
        long delivered=Long.MIN_VALUE;
    }
    private StormLodServer() {}
    public static void preference(ServerPlayer p,int distance){PREFERENCES.put(p.getUUID(),Math.clamp(distance,0,8192));}
    public static void center(ServerLevel level,ClientboundStormCenterPacket packet){
        State s=LEVELS.computeIfAbsent(level,k->new State());
        if(s.identity!=null&&!s.identity.equals(packet.identity())){stop(level);s=LEVELS.computeIfAbsent(level,k->new State());}
        s.identity=packet.identity();s.centers.offer(s.identity,packet.sample());
    }
    public static void death(ServerLevel level,int member){var s=LEVELS.get(level);if(s!=null&&member>=0&&member<AllayStormData.MAX_COUNT)s.deaths.set(member);}
    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void tick(LevelTickEvent.Post e){
        if(!(e.getLevel() instanceof ServerLevel level))return;
        var data=level.getData(CMIAttachments.STORM_DATA.get());
        State s=LEVELS.get(level);
        if(!data.active){stop(level);return;}
        if(s==null||s.centers.current()==null)return;
        var current=s.centers.current();boolean update=s.delivered!=current.tick();
        var present=new HashSet<UUID>();
        for(var p:level.players()){
            UUID id=p.getUUID();int limit=StormLodRules.limit(ServerConfig.stormLodMaxDistance,PREFERENCES.getOrDefault(id,0));
            boolean previous=s.recipients.containsKey(id);
            double distance=Math.sqrt(p.distanceToSqr(current.x(),current.y(),current.z()));
            if(!StormLodRules.subscribed(previous,distance,1.2*AllayStormData.vortexRadius(data.count)+16,limit)){
                if(previous)PacketDistributor.sendToPlayer(p,remove(s.identity,s.recipients.get(id).subscription()));continue;
            }
            present.add(id);
            Observer observer=s.recipients.get(id);
            if(!previous||observer.limit()!=limit){
                observer=new Observer(limit,++nextSubscription);
                PacketDistributor.sendToPlayer(p,new StormLodPacket(s.identity,observer.subscription(),StormLodPacket.INIT,limit,StormVisualDefinition.of(data),s.centers.previous(),current,data.dead));
            }else if(!AllayStormManager.fullObserver(level,id)){
                boolean supplied=com.iridium126.createmanaindustry.content.allaystorm.rift.StormRiftServer.centerObserver(level,id);
                if(update&&!supplied||!s.deaths.isEmpty())
                    PacketDistributor.sendToPlayer(p,new StormLodPacket(s.identity,observer.subscription(),StormLodPacket.UPDATE,0,null,null,update&&!supplied?current:null,s.deaths));
            }
            s.recipients.put(id,observer);
        }
        s.recipients.keySet().retainAll(present);s.deaths.clear();s.delivered=current.tick();
    }
    private static StormLodPacket remove(StormIdentity id,long subscription){return new StormLodPacket(id,subscription,StormLodPacket.REMOVE,0,null,null,null,new BitSet());}
    public static void stop(ServerLevel level){
        State s=LEVELS.remove(level);if(s==null||s.identity==null)return;
        for(var p:level.players())if(s.recipients.containsKey(p.getUUID()))PacketDistributor.sendToPlayer(p,remove(s.identity,s.recipients.get(p.getUUID()).subscription()));
    }
    @SubscribeEvent public static void unload(LevelEvent.Unload e){if(e.getLevel() instanceof ServerLevel l)stop(l);}
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent e){PREFERENCES.remove(e.getEntity().getUUID());}
    @SubscribeEvent public static void stopped(ServerStoppedEvent e){LEVELS.clear();PREFERENCES.clear();}
}
