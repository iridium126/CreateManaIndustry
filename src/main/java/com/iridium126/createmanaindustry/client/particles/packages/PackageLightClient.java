package com.iridium126.createmanaindustry.client.particles.packages;

import com.iridium126.createmanaindustry.content.logistics.gpupackage.*;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.*;
import com.simibubi.create.AllPartialModels;
import java.util.*;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.*;
import net.minecraft.core.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Entity-free backing display/input, also available while GPU resources are rebuilding. */
public final class PackageLightClient {
    private record Visual(ClientboundLightPackagePacket.Row row,PackageLease.Pose previous,long tick) {}
    private static final Map<PackageLease.Identity,Visual> entries=new LinkedHashMap<>();
    private static final Set<PackageLease.Identity> observed=new HashSet<>();
    private static final Set<PackageLease.Identity> detached=new HashSet<>();
    private static ClientLevel world;
    private static void world(){var current=Minecraft.getInstance().level;if(world!=current){world=current;entries.clear();observed.clear();detached.clear();}}
    public static void detached(ClientboundPackagePacket packet){world();if(world!=null&&world.dimension().location().equals(packet.dimension()))detached.add(packet.baseline().identity());}
    static void observed(PackageLease.Identity identity,boolean value){world();if(value)observed.add(identity);else observed.remove(identity);}
    public static void receive(ClientboundLightPackagePacket packet){world();if(world==null||!world.dimension().location().equals(packet.dimension()))return;for(var row:packet.rows()){if(row.removed()){entries.remove(row.identity());detached.remove(row.identity());continue;}var old=entries.get(row.identity());if(old==null&&entries.size()>=131072)continue;entries.put(row.identity(),new Visual(row,old==null?row.pose():old.row.pose(),world.getGameTime()));}}
    public static boolean has(PackageLease.Identity identity){world();return entries.containsKey(identity)||detached.contains(identity);}
    public static void render(RenderLevelStageEvent event) {
        world();if(world==null||entries.isEmpty())return;
        var camera=event.getCamera().getPosition();var stack=event.getPoseStack();var buffers=Minecraft.getInstance().renderBuffers().bufferSource();float partial=event.getPartialTick().getGameTimeDeltaPartialTick(false);
        for(var entry:entries.values()) {
            var row=entry.row;if(PackageRenderOwnership.hasIdentity(row.identity())||observed.contains(row.identity()))continue;
            var p=row.pose();var before=world.getGameTime()==entry.tick?entry.previous:p;var model=AllPartialModels.PACKAGES.get(row.model());if(model==null)continue;
            double x=net.minecraft.util.Mth.lerp(partial,before.x(),p.x()),y=net.minecraft.util.Mth.lerp(partial,before.y(),p.y()),z=net.minecraft.util.Mth.lerp(partial,before.z(),p.z());
            var bounds=new AABB(x-row.width()*.5,y,z-row.width()*.5,x+row.width()*.5,y+row.height(),z+row.width()*.5);if(!event.getFrustum().isVisible(bounds))continue;
            int light=LevelRenderer.getLightColor(world,BlockPos.containing(x,y+row.height()*.85,z));
            stack.pushPose();stack.translate(x-camera.x,y-camera.y,z-camera.z);
            CachedBuffers.partial(model,Blocks.AIR.defaultBlockState()).translate(-.5,0,-.5).rotateCentered(-net.createmod.catnip.math.AngleHelper.rad(p.yaw()+90),Direction.UP).light(light).nudge((int)row.identity().id()).renderInto(stack,buffers.getBuffer(RenderType.solid()));stack.popPose();
        }
    }
    public static boolean input(boolean attack) {
        world();var mc=Minecraft.getInstance();if(world==null||mc.player==null||mc.screen!=null||mc.isPaused()||mc.player.isHandsBusy()||PackageFreeInteractionClient.nativeReplay())return false;
        if(mc.getConnection()==null||!mc.getConnection().hasChannel(ServerboundLightPackageInteraction.TYPE))return false;
        var from=mc.player.getEyePosition();var end=from.add(mc.player.getViewVector(1).scale(mc.player.entityInteractionRange()));double distance=mc.hitResult==null||mc.hitResult.getType()==HitResult.Type.MISS?from.distanceToSqr(end):from.distanceToSqr(mc.hitResult.getLocation());
        PackageLease.Identity selected=null;
        for(var e:entries.values()){if(PackageRenderOwnership.hasIdentity(e.row.identity()))continue;var row=e.row;var p=row.pose();var hit=new AABB(p.x()-row.width()*.5,p.y(),p.z()-row.width()*.5,p.x()+row.width()*.5,p.y()+row.height(),p.z()+row.width()*.5).clip(from,end).orElse(null);if(hit!=null&&from.distanceToSqr(hit)<distance){distance=from.distanceToSqr(hit);selected=row.identity();}}
        if(selected==null)return false;
        var hand=mc.player.getMainHandItem().isEmpty()?InteractionHand.MAIN_HAND:InteractionHand.OFF_HAND;
        if(!attack&&!mc.player.getItemInHand(hand).isEmpty())return false;
        PacketDistributor.sendToServer(new ServerboundLightPackageInteraction(selected,attack,hand));mc.player.swing(hand);return true;
    }
}
