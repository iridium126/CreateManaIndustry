package com.iridium126.createmanaindustry.client.allaystorm.rift;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.content.allaystorm.rift.*;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

/** Post-arrival confirmation only. Retains the ordinary dimension loading screen. */
@EventBusSubscriber(modid=CreateManaIndustry.MODID,value=Dist.CLIENT)
public final class RiftArrivalClient {
    private static final ArrayDeque<UUID> seen=new ArrayDeque<>();
    private static RiftArrivalPacket pending;
    private static double started=Double.NaN;
    private RiftArrivalClient() {}
    public static void receive(RiftArrivalPacket packet) {
        if(!packet.dimension().equals(AllvrDimensions.ALLAY_LEVEL.location())||seen.contains(packet.transaction()))return;
        seen.addLast(packet.transaction());while(seen.size()>64)seen.removeFirst();
        pending=packet;started=Double.NaN;
    }
    @SubscribeEvent public static void overlay(RenderGuiEvent.Pre event) {
        var mc=Minecraft.getInstance();
        if(pending==null||mc.level==null||mc.player==null||mc.screen!=null||!mc.level.dimension().location().equals(pending.dimension()))return;
        double clock=mc.level.getGameTime()+event.getPartialTick().getGameTimeDeltaPartialTick(false);
        if(Double.isNaN(started)) {
            if(!mc.level.hasChunkAt(mc.player.blockPosition())||!tracked(pending))return;
            AllaySkyProvider.context(new AllaySkyPacket(pending.sky()));
            if(mc.getConnection()==null||!mc.getConnection().hasChannel(RiftReadyPacket.TYPE))return;
            PacketDistributor.sendToServer(new RiftReadyPacket(pending.transaction())); started=clock;
        }
        double progress=Math.clamp((clock-started)/12.0,0,1);
        int alpha=(int)Math.round(255*.35*(1-progress)*(1-progress));
        if(alpha>0)event.getGuiGraphics().fill(0,0,mc.getWindow().getGuiScaledWidth(),mc.getWindow().getGuiScaledHeight(),(alpha<<24)|0xc7faff);
        if(progress>=1)pending=null;
    }
    private static boolean tracked(RiftArrivalPacket packet) {
        var available=new HashMap<UUID,net.minecraft.world.entity.Entity>();
        for(var entity:Minecraft.getInstance().level.entitiesForRendering())available.put(entity.getUUID(),entity);
        if(!available.keySet().containsAll(packet.trackedEntities()))return false;
        for(var edge:packet.mounts()) {var passenger=available.get(edge.passenger());var vehicle=available.get(edge.vehicle());if(passenger==null||vehicle==null||passenger.getVehicle()!=vehicle||vehicle.getPassengers().indexOf(passenger)!=edge.order())return false;}
        if(packet.trackedStructures().isEmpty())return true;
        return net.neoforged.fml.ModList.get().isLoaded("sable")&&SableTracking.ready(packet.trackedStructures());
    }
    private static final class SableTracking {
        static boolean ready(List<UUID> expected) {
            try {
                var container=dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(Minecraft.getInstance().level);
                if(container==null)return false;
                for(UUID id:expected) {
                    var sub=container.getSubLevel(id);
                    if(!(sub instanceof dev.ryanhcode.sable.sublevel.ClientSubLevel client)||!client.isFinalized()||client.isRemoved())return false;
                }
                return true;
            } catch(LinkageError unsupportedAbi) { return false; }
        }
    }
    public static void clear() { pending=null;started=Double.NaN;seen.clear(); }
}
