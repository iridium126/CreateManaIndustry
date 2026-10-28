package com.iridium126.createmanaindustry.client.allaystorm.lod;

import java.util.BitSet;
import com.iridium126.createmanaindustry.content.allaystorm.lod.StormLodStream;
import com.iridium126.createmanaindustry.content.allaystorm.*;
import com.iridium126.createmanaindustry.content.allaystorm.network.*;
import com.iridium126.createmanaindustry.content.allaystorm.rift.StormVisualDefinition;
import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.network.PacketDistributor;

/** Network mirror only. Member positions never cross the CPU/network boundary. */
public final class StormLodClient {
    public static final StormCenterTimeline CENTERS=new StormCenterTimeline();
    public static StormIdentity identity;
    public static StormVisualDefinition definition;
    public static final BitSet DEAD=new BitSet();
    public static int serverLimit, revision;
    private static final StormLodStream STREAM=new StormLodStream();
    private static ClientLevel world;
    private static int sent=-1;
    private StormLodClient() {}
    public static void install(){
        StormLodPacket.installClientHandler(StormLodClient::receive);
        NeoForge.EVENT_BUS.addListener(StormLodClient::tick);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e)->{world=null;sent=-1;clear();STREAM.disconnect();});
    }
    private static void tick(ClientTickEvent.Post e){
        var mc=Minecraft.getInstance();
        if(mc.level!=world){world=mc.level;clear();}
        if(mc.getConnection()==null||mc.player==null){sent=-1;return;}
        int distance=ClientConfig.particleEnabled?ClientConfig.stormLodDistance:0;
        if(sent!=distance){PacketDistributor.sendToServer(new StormLodPreferencePacket(distance));sent=distance;}
        if(distance==0)clear();
    }
    private static void receive(StormLodPacket p){
        var mc=Minecraft.getInstance();if(mc.level==null||!p.identity().dimension().equals(mc.level.dimension().location()))return;
        if(world!=mc.level){world=mc.level;clear();}
        if(p.action()==StormLodPacket.REMOVE){if(STREAM.accepts(p.subscription())&&p.identity().equals(identity))clear();return;}
        if(p.action()==StormLodPacket.INIT){
            if(!STREAM.initialize(p.subscription()))return;
            CENTERS.clear();
            if(!CENTERS.select(p.identity()))return;
            identity=p.identity();definition=p.definition();serverLimit=p.limit();DEAD.clear();
            CENTERS.offer(identity,p.previous(),p.current());
        }else if(!STREAM.accepts(p.subscription())||!p.identity().equals(identity))return;
        if(p.current()!=null)CENTERS.offer(identity,p.current());
        DEAD.or(p.deaths());if(p.action()==StormLodPacket.INIT||!p.deaths().isEmpty())revision++;
    }
    /** Full-range activation may contain deaths that happened before this observer became active. */
    public static void state(ClientboundStormStatePacket p){
        if(!p.identity().equals(identity))return;
        if(p.action()==ClientboundStormStatePacket.ACTION_STOP){clear();return;}
        if(p.action()==ClientboundStormStatePacket.ACTION_ACTIVATE && p.deadBitmap().length>0){
            DEAD.or(BitSet.valueOf(p.deadBitmap()));revision++;
        }
    }
    public static void center(StormIdentity id,StormCenterSample previous,StormCenterSample current){if(id.equals(identity))CENTERS.offer(id,previous,current);}
    public static void center(ClientboundStormCenterPacket p){if(p.identity().equals(identity))CENTERS.offer(identity,p.sample());}
    public static void damage(ClientboundStormDamagePacket p){
        if(p.died()&&new StormIdentity(p.dimension(),p.epoch()).equals(identity)){DEAD.set(p.memberIdx());revision++;}
    }
    public static boolean active(){return identity!=null&&definition!=null&&ClientConfig.particleEnabled&&ClientConfig.stormLodDistance>0&&serverLimit>0;}
    public static void clear(){STREAM.clear();identity=null;definition=null;serverLimit=0;DEAD.clear();CENTERS.clear();revision++;}
}
