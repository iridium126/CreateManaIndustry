package com.iridium126.createmanaindustry.client.particles.packages;

import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageChainAuthority;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.*;
import com.iridium126.createmanaindustry.mixin.vanilla.MinecraftInvoker;
import com.simibubi.create.foundation.utility.RaycastHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.network.PacketDistributor;

/** Create's package input boundary. Query misses replay outside the engine's GL pass; a
 * request already sent to the server is never replayed as a second native inventory action. */
public final class PackageChainInteractionClient implements AutoCloseable {
    private record Context(ClientLevel level,LocalPlayer player,HitResult hit,int slot,ItemStack item) {}
    private static boolean replaying;
    private final PackageChainAcquisitionGpu acquisition;
    private final PackageChainUseQueue queue;
    private final double ox,oy,oz;
    private boolean closed;
    public PackageChainInteractionClient(PackageChainAcquisitionGpu acquisition,double ox,double oy,double oz) {
        this.acquisition=acquisition;this.ox=ox;this.oy=oy;this.oz=oz;queue=new PackageChainUseQueue(acquisition.epoch());
        acquisition.pickResults(this::completed);
    }
    public boolean onUse() {
        var mc=Minecraft.getInstance();
        if(closed || replaying || mc.player==null || mc.level==null || mc.screen!=null || mc.isPaused())return false;
        if(PackageFreeInteractionClient.nativeReplay())return precomputed(PackageFreeInteractionClient.chainPick(this));
        if(queue.phase()!=PackageChainUseQueue.Phase.EMPTY)return true;
        if(acquisition.activeCount()==0 || mc.getConnection()==null || !mc.getConnection().hasChannel(ServerboundChainInteractionPacket.TYPE))return false;
        var player=mc.player;var from=player.getEyePosition();
        var to=RaycastHelper.getTraceTarget(player,player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE)+1,from);var delta=to.subtract(from);
        var ray=new PackagePoseQueryGpu.Ray((float)(from.x-ox),(float)(from.y-oy),(float)(from.z-oz),(float)delta.x,(float)delta.y,(float)delta.z);
        return queue.enqueue(ray,new Context(mc.level,player,mc.hitResult,player.getInventory().selected,player.getMainHandItem().copy()));
    }
    public static boolean nativeReplay(){return replaying;}
    public boolean hasPackages(){return !closed&&acquisition.activeCount()>0;}
    /** A mixed free/chain input already queried both domains in parallel. Consume its chain
     * result at Create's original package stage; never enqueue another GPU query on replay. */
    private boolean precomputed(PackagePoseQueryGpu.Result result) {
        if(result==null||!result.present())return false;
        var mc=Minecraft.getInstance();
        if(mc.getConnection()==null||!mc.getConnection().hasChannel(ServerboundChainInteractionPacket.TYPE))return false;
        if(queue.phase()!=PackageChainUseQueue.Phase.EMPTY)return true;
        var ray=PackageFreeInteractionClient.chainRay();if(ray==null)return false;
        var player=mc.player;
        if(!queue.enqueue(ray,new Context(mc.level,player,mc.hitResult,player.getInventory().selected,player.getMainHandItem().copy())))return true;
        var use=queue.queued();var request=acquisition.interaction(result,use.transaction());
        if(request==null){queue.clear();return false;}
        queue.submitted(use);queue.completed(new PackagePoseQueryGpu.Completed(0,0,0,PackagePoseQueryGpu.Kind.PICK,use,java.util.List.of(result)));
        queue.sent(request);PacketDistributor.sendToServer(new ServerboundChainInteractionPacket(request));return true;
    }
    /** Called only after the shared physical/pool publication committed. Full banks keep QUEUED. */
    public void committed(long generation) {
        if(closed)return;var use=queue.queued();if(use!=null && acquisition.pick(use,generation))queue.submitted(use);
    }
    private void completed(PackagePoseQueryGpu.Completed result) {
        if(closed || !queue.completed(result))return;
        var pose=queue.result();if(pose==null)return;
        var request=acquisition.interaction(pose,queue.use().transaction());
        if(request==null){queue.replay();return;}
        var context=(Context)queue.use().context();var mc=Minecraft.getInstance();
        if(mc.level!=context.level || mc.player!=context.player || mc.getConnection()==null){queue.clear();return;}
        queue.sent(request);PacketDistributor.sendToServer(new ServerboundChainInteractionPacket(request));
    }
    public void acknowledge(ClientboundChainInteractionPacket packet) {
        if(closed || !queue.acknowledge(packet))return;
        if(packet.result()==PackageChainAuthority.Result.FAILED)PackageAuthorityClient.closeAll("Chain pickup transaction failed",true);
    }
    /** Main-thread tick, outside particle submission. Avoid nested Minecraft work in a shader pass. */
    public void tick() {
        if(closed)return;var phase=queue.phase();
        if(phase!=PackageChainUseQueue.Phase.REPLAY && phase!=PackageChainUseQueue.Phase.TIMED_OUT)return;
        var use=queue.use();boolean sent=queue.sentToServer();queue.clear();
        if(phase==PackageChainUseQueue.Phase.TIMED_OUT)PackageAuthorityClient.closeAll(sent?"Chain pickup network confirmation timed out":"Chain pickup processing exceeded two ticks",true);
        if(!sent)replay(use);
    }
    private static void replay(PackageChainUseQueue.Use use) {
        if(use==null || !(use.context() instanceof Context context))return;
        var mc=Minecraft.getInstance();
        if(mc.player!=context.player || mc.level!=context.level || mc.screen!=null || mc.getConnection()==null || context.hit==null
                || mc.player.getInventory().selected!=context.slot || !ItemStack.isSameItemSameComponents(mc.player.getMainHandItem(),context.item))return;
        var previous=mc.hitResult;replaying=true;
        try{mc.hitResult=context.hit;((MinecraftInvoker)(Object)mc).createmanaindustry$invokeStartUseItem();}
        finally{mc.hitResult=previous;replaying=false;}
    }
    public long networkNanos(){return queue.latestNetworkNanos();}
    @Override public void close(){closed=true;queue.clear();}
}
