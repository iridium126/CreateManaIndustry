package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.*;
import java.util.function.Consumer;
import com.iridium126.createmanaindustry.mixin.packages.NativeMotionPacketAccessor;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageLease;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageRegion;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageNativeMembershipRegistry;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageObserverFeed;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ClientboundPackagePacket;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ClientboundPackageObserverPacket;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ServerboundPackageObserverPacket;
import com.simibubi.create.content.logistics.box.PackageEntity;
import com.simibubi.create.content.logistics.box.PackageItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.*;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/** Native packet adapter, called AFTER vanilla's main-thread handlers. Existing vanilla codec,
 * interpolation targets and interaction entity remain current. The optional membership feed
 * adds lifecycle/control messages, never duplicate poses or per-frame world/entity scans.
 * World runtime owns this adapter and its GPU staging resources. */
public final class PackageNativeObserverClient implements AutoCloseable,PackageNativeObserverController.Lifecycle,PackageNativeMembershipRegistry.Sink {
    private static PackageNativeObserverClient current;
    private final ClientLevel level;
    private final PackageNativeObserverController controller;
    private final Consumer<String> failure;
    private final Thread owner=Thread.currentThread();
    private final Set<PackageRegion> subscriptions;
    private final PackageNativeMembershipRegistry membership;
    private record Arrival(ClientboundPackageObserverPacket packet,long receipt) {}
    private final ArrayDeque<Arrival> inbox=new ArrayDeque<>();
    private int queuedRecords;
    private long requestedTick=Long.MIN_VALUE;
    private record AuthorityKey(PackageRegion region,long epoch,int index,PackageLease.Identity identity,long leaseEpoch) {}
    private record ObservedMember(AuthorityKey owner,int entityId,UUID uuid,ResourceLocation model,float width,float height) {}
    private final Map<AuthorityKey,UUID> authorities=new HashMap<>();
    private final Map<UUID,Integer> authorityCounts=new HashMap<>();
    private final Map<UUID,ObservedMember> confirmedMembers=new HashMap<>();
    private boolean closed;
    public PackageNativeObserverClient(ClientLevel level,PackageMixedPhysicsGpu physics,PackagePoolGpu pool,Map<ResourceLocation,PackageModelCache.Style> styles,
                                       long epoch,Consumer<String> failure) {
        if(current!=null)throw new IllegalStateException("Native observer world already attached");
        this.level=Objects.requireNonNull(level);this.failure=Objects.requireNonNull(failure);
        var p=Objects.requireNonNull(Minecraft.getInstance().player).position();
        int x=(int)Math.floor(p.x/64-.5),y=(int)Math.floor(p.y/64-.5),z=(int)Math.floor(p.z/64-.5);
        var interest=new LinkedHashSet<PackageRegion>();
        for(int dx=0;dx<2;dx++)for(int dy=0;dy<2;dy++)for(int dz=0;dz<2;dz++)interest.add(new PackageRegion(x+dx,y+dy,z+dz));
        subscriptions=Set.copyOf(interest);membership=new PackageNativeMembershipRegistry(level.dimension().location(),subscriptions,131072);
        controller=new PackageNativeObserverController(physics,pool,styles,epoch,this);current=this;
    }
    private static PackageNativeObserverClient live(){var c=current;return c!=null&&!c.closed&&c.level==Minecraft.getInstance().level?c:null;}
    private boolean eligible(PackageEntity entity) {
        var member=confirmedMembers.get(entity.getUUID());
        return entity.level()==level&&level.getEntity(entity.getId())==entity&&!entity.isRemoved()&&!entity.isPassenger()
            &&!entity.isControlledByLocalInstance()&&entity.insertionDelay>=20&&PackageItem.isPackage(entity.box)
            &&member!=null&&member.entityId()==entity.getId()&&member.model().equals(BuiltInRegistries.ITEM.getKey(entity.box.getItem()))
            &&member.width()==entity.getBbWidth()&&member.height()==entity.getBbHeight()
            &&!authorityCounts.containsKey(entity.getUUID())&&!PackageRenderOwnership.authorityOwned(entity);
    }
    /** Called only by the validated membership consumer AFTER the server confirms GPU_OWNED.
     * Native poses alone cannot distinguish a chute/machine package from a leased free body.
     * Ordinary Create-owned packages remain native. */
    private void confirmMember(PackageRegion region,long authorityEpoch,int index,PackageLease.Identity identity,long leaseEpoch,int entityId,UUID uuid,
                              ResourceLocation model,float width,float height) {
        open();
        if(authorityEpoch<=0||index<0||identity==null||leaseEpoch<=0||uuid==null||model==null||!Float.isFinite(width)||!Float.isFinite(height)||width<=0||height<=0||width>16||height>16)
            throw new IllegalArgumentException("Native observer membership");
        if(!confirmedMembers.containsKey(uuid)&&confirmedMembers.size()>=131072)throw new IllegalStateException("Native membership capacity");
        var member=new ObservedMember(new AuthorityKey(Objects.requireNonNull(region),authorityEpoch,index,identity,leaseEpoch),entityId,uuid,model,width,height);
        var old=confirmedMembers.put(uuid,member);if(old!=null&&!old.equals(member))controller.remove(old.entityId(),old.uuid());
        if(level.getEntity(entityId) instanceof PackageEntity e&&e.getUUID().equals(uuid))observe(e,System.nanoTime());
    }
    private void retireMember(PackageRegion region,long authorityEpoch,int index,PackageLease.Identity identity,long leaseEpoch,int entityId,UUID uuid) {
        open();
        var member=confirmedMembers.get(uuid);if(member!=null&&member.entityId()==entityId&&member.owner().equals(new AuthorityKey(region,authorityEpoch,index,identity,leaseEpoch))) {
            confirmedMembers.remove(uuid,member);controller.remove(entityId,uuid);
        }
    }
    @Override public void confirm(PackageRegion region,long epoch,PackageObserverFeed.Member<ClientboundPackageObserverPacket.Visual> member) {
        var v=member.metadata();confirmMember(region,epoch,member.index(),member.identity(),member.leaseEpoch(),v.entityId(),v.entityUuid(),v.model(),v.width(),v.height());
    }
    @Override public void retire(PackageRegion region,long epoch,PackageObserverFeed.Member<ClientboundPackageObserverPacket.Visual> member) {
        var v=member.metadata();retireMember(region,epoch,member.index(),member.identity(),member.leaseEpoch(),v.entityId(),v.entityUuid());
    }
    /** Called by the typed main-thread payload handler; no GL mutations before prepare. */
    public static void receiveMembership(ClientboundPackageObserverPacket packet) {
        var c=live();if(c==null||!c.level.dimension().location().equals(packet.dimension())||!c.subscriptions.contains(packet.region()))return;
        c.open();int records=packet.baselines().size()+packet.changes().size();
        if(c.inbox.size()>=1024||records>131072-c.queuedRecords){c.failure.accept("Native membership inbox exhausted");return;}
        c.inbox.addLast(new Arrival(packet,System.nanoTime()));c.queuedRecords+=records;
    }
    private void requestMembership() {
        var connection=Minecraft.getInstance().getConnection();long tick=level.getGameTime();
        if(connection==null||!connection.hasChannel(ServerboundPackageObserverPacket.TYPE)
                ||!connection.hasChannel(ClientboundPackageObserverPacket.TYPE)||requestedTick!=Long.MIN_VALUE&&tick-requestedTick<40)return;
        requestedTick=tick;
        for(var region:subscriptions)if(membership.stream(region)==0)
            PacketDistributor.sendToServer(new ServerboundPackageObserverPacket(ServerboundPackageObserverPacket.SUBSCRIBE_NATIVE,region,0));
    }
    /** Existing members don't allocate snapshots or recalculate poses for each packet. */
    private PackageNativeObserverController.Observation observe(PackageEntity entity,long now) {
        if(!eligible(entity)){controller.remove(entity.getId(),entity.getUUID());return PackageNativeObserverController.Observation.IGNORED;}
        if(controller.tracked(entity.getId(),entity.getUUID()))return PackageNativeObserverController.Observation.EXISTING;
        var base=entity.getPositionCodec().getBase();var velocity=entity.getDeltaMovement();
        var model=BuiltInRegistries.ITEM.getKey(entity.box.getItem());
        var probe=net.minecraft.core.BlockPos.containing(entity.getLightProbePosition(1));
        int light=net.minecraft.client.renderer.LightTexture.pack(level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK,probe),level.getBrightness(net.minecraft.world.level.LightLayer.SKY,probe));
        var b=new PackageNativeObserverController.Baseline(entity.getId(),entity.getUUID(),model,entity.getBbWidth(),entity.getBbHeight(),light,
            base.x,base.y,base.z,velocity.x,velocity.y,velocity.z,entity.lerpTargetYRot(),entity.onGround());
        return controller.observe(b,now);
    }
    public static void entityAvailable(PackageEntity entity){if(!(entity.level() instanceof ClientLevel))return;var c=live();if(c!=null&&entity.level()==c.level)c.observe(entity,System.nanoTime());}
    public static void changed(PackageEntity entity){if(!(entity.level() instanceof ClientLevel))return;var c=live();if(c!=null&&entity.level()==c.level)c.controller.remove(entity.getId(),entity.getUUID());}
    public static void added(ClientboundAddEntityPacket packet){var c=live();if(c!=null&&c.level.getEntity(packet.getId()) instanceof PackageEntity e)c.observe(e,System.nanoTime());}
    public static void moved(ClientboundMoveEntityPacket packet) {
        var c=live();if(c==null||!(packet.getEntity(c.level) instanceof PackageEntity e))return;long now=System.nanoTime();
        if(!c.eligible(e)){c.controller.remove(e.getId(),e.getUUID());return;}
        if(!c.controller.move(e.getId(),e.getUUID(),packet.getXa(),packet.getYa(),packet.getZa(),packet.hasPosition(),packet.hasRotation(),packet.getyRot(),packet.isOnGround(),now))c.observe(e,now);
    }
    public static void teleported(ClientboundTeleportEntityPacket packet) {
        var c=live();if(c==null||!(c.level.getEntity(packet.getId()) instanceof PackageEntity e))return;long now=System.nanoTime();
        if(!c.eligible(e)){c.controller.remove(e.getId(),e.getUUID());return;}
        if(!c.controller.teleport(e.getId(),e.getUUID(),packet.getX(),packet.getY(),packet.getZ(),packet.getyRot(),packet.isOnGround(),now))c.observe(e,now);
    }
    public static void motion(ClientboundSetEntityMotionPacket packet) {
        var c=live();if(c==null||!(c.level.getEntity(packet.getId()) instanceof PackageEntity e))return;long now=System.nanoTime();
        if(!c.eligible(e)){c.controller.remove(e.getId(),e.getUUID());return;}
        var raw=(NativeMotionPacketAccessor)packet;
        if(!c.controller.motion(e.getId(),e.getUUID(),raw.cmi$rawX(),raw.cmi$rawY(),raw.cmi$rawZ(),now))c.observe(e,now);
    }
    public static void removed(ClientboundRemoveEntitiesPacket packet){var c=live();if(c!=null)for(int id:packet.getEntityIds())c.controller.remove(id);}
    public static void passengers(ClientboundSetPassengersPacket packet){var c=live();if(c!=null)for(int id:packet.getPassengers())if(c.level.getEntity(id) instanceof PackageEntity e)c.controller.remove(id,e.getUUID());}
    /** OFFER suspends observer introduction before the independent authority acquisition starts. */
    public void authority(ClientboundPackagePacket packet) {
        var key=authorityKey(packet);
        if(packet.action()==ClientboundPackagePacket.OFFER) {
            if(authorities.putIfAbsent(key,packet.entityUuid())==null)authorityCounts.merge(packet.entityUuid(),1,Integer::sum);
            controller.remove(packet.entityId(),packet.entityUuid());
        } else if(packet.action()==ClientboundPackagePacket.RELEASED)releaseAuthority(key);
    }
    private void releaseAuthority(AuthorityKey key){var uuid=authorities.remove(key);if(uuid!=null)authorityCounts.computeIfPresent(uuid,(u,n)->n==1?null:n-1);}
    private static AuthorityKey authorityKey(ClientboundPackagePacket p){return new AuthorityKey(p.region(),p.epoch(),p.baseline().index(),p.baseline().identity(),p.baseline().leaseEpoch());}
    public static void authorityReleased(ClientboundPackagePacket offer){var c=live();if(c!=null)c.releaseAuthority(authorityKey(offer));}
    public void prepare(long now){
        open();requestMembership();
        long deadline=System.nanoTime()+250_000L;int records=0;
        // A packet is atomic, so this is a soft budget checked between bounded packets. Never
        // scan a whole initial subscription or wait for GL completion on the client thread.
        for(int i=0;i<64&&!inbox.isEmpty()&&records<1024&&(i==0||System.nanoTime()<deadline);i++) {
            var arrival=inbox.removeFirst();queuedRecords-=arrival.packet().baselines().size()+arrival.packet().changes().size();
            records+=arrival.packet().baselines().size()+arrival.packet().changes().size();
            if(now-arrival.receipt()>100_000_000L||membership.apply(arrival.packet(),this)==PackageNativeMembershipRegistry.Result.RESYNC) {
                failure.accept("Native membership sequence/identity/processing budget requires rebuild");return;
            }
        }
        controller.prepare(System.nanoTime());if(controller.failure()!=null)failure.accept(controller.failure());
    }
    public void committed(long generation){controller.committed(generation);}
    public int active(){return controller.active();}
    @Override public boolean admitted(PackageNativeObserverController.Baseline baseline,long epoch,long id,long generation,int slot) {
        if(closed||Minecraft.getInstance().level!=level||!(level.getEntity(baseline.entityId()) instanceof PackageEntity e)
                ||!e.getUUID().equals(baseline.uuid())||!eligible(e)||!BuiltInRegistries.ITEM.getKey(e.box.getItem()).equals(baseline.model())
                ||e.getBbWidth()!=baseline.width()||e.getBbHeight()!=baseline.height())return false;
        PackageRenderOwnership.claimNativeAfterAdmission(e,epoch,id,generation,slot);return true;
    }
    @Override public void released(PackageNativeObserverController.Baseline b,long epoch,long id,long generation){PackageRenderOwnership.releaseNative(b.entityId(),b.uuid(),epoch,id,generation);}
    @Override public void closed(long epoch){PackageRenderOwnership.clearNative(epoch);}
    @Override public void failed(String reason){failure.accept(reason);}
    private void open(){if(closed||Thread.currentThread()!=owner||Minecraft.getInstance().level!=level)throw new IllegalStateException("Native membership off client thread/world or closed");}
    @Override public void close(){if(closed)return;closed=true;if(current==this)current=null;controller.close();authorities.clear();authorityCounts.clear();confirmedMembers.clear();inbox.clear();queuedRecords=0;}
}
