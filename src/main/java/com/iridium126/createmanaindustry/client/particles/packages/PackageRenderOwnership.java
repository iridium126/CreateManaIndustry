package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageAuthorityRegion;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageLease;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageRegion;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ClientboundPackagePacket;
import com.simibubi.create.content.logistics.box.PackageEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Render arbitration for Create's entity renderer and Flywheel visual. A claim may be published
 * only after the authoritative GPU admission and matching ACTIVE baseline are confirmed.
 * Hot render checks use an entity-id lookup and also compare the world and UUID, so recycling
 * an entity id or changing dimension cannot hide a different package.
 */
public final class PackageRenderOwnership {
    private record Claim(ClientLevel level,int entityId,UUID uuid,PackageRegion region,long epoch,
                         PackageLease.Identity identity,int index,long leaseEpoch,long revision) {}
    private static final ConcurrentHashMap<Integer,Claim> byEntity=new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<PackageLease.Identity,Claim> byIdentity=new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<PackageLease.Identity,PackageFreeNativeRecovery> recoveries=new ConcurrentHashMap<>();
    private record NativeClaim(ClientLevel level,int entityId,UUID uuid,long epoch,long id,long generation) {}
    private static final ConcurrentHashMap<Integer,NativeClaim> nativeClaims=new ConcurrentHashMap<>();
    private PackageRenderOwnership() {}

    /** The caller must already have verified GPU admission for the matching committed draw. */
    public static void claimAfterAdmission(PackageEntity entity,ClientboundPackagePacket offer,
                                           ClientboundPackagePacket active,int slotPlusOne) {
        if(!(entity.level() instanceof ClientLevel level)||entity.getId()!=offer.entityId()||!entity.getUUID().equals(offer.entityUuid()))throw new IllegalArgumentException("Package entity admission");
        claimLightAfterAdmission(offer,active,slotPlusOne);
    }
    public static void claimLightAfterAdmission(ClientboundPackagePacket offer,ClientboundPackagePacket active,int slotPlusOne) {
        var level=Minecraft.getInstance().level;
        if(level==null||offer.action()!=ClientboundPackagePacket.OFFER||active.action()!=ClientboundPackagePacket.ACTIVE||slotPlusOne<=0||!level.dimension().location().equals(offer.dimension())||!offer.dimension().equals(active.dimension())||!offer.region().equals(active.region())||offer.epoch()!=active.epoch()||offer.regionRevision()!=active.regionRevision()||offer.baseline().index()!=active.baseline().index()||!offer.baseline().identity().equals(active.baseline().identity())||offer.baseline().leaseEpoch()!=active.baseline().leaseEpoch())throw new IllegalArgumentException("Package light admission");
        var claim=new Claim(level,offer.entityId(),offer.entityUuid(),active.region(),active.epoch(),active.baseline().identity(),active.baseline().index(),active.baseline().leaseEpoch(),active.baseline().revision());
        recoveries.remove(claim.identity);if(claim.entityId>=0){var old=byEntity.put(claim.entityId,claim);if(old!=null)byIdentity.remove(old.identity,old);}var old=byIdentity.put(claim.identity,claim);if(old!=null&&old.entityId>=0)byEntity.remove(old.entityId,old);
    }
    public static boolean hasIdentity(PackageLease.Identity identity){var claim=byIdentity.get(identity);return claim!=null&&claim.level==Minecraft.getInstance().level;}
    public static void serverReleased(PackageRegion region,long epoch,PackageAuthorityRegion.Baseline baseline) {
        if(baseline==null)return;var claim=byIdentity.get(baseline.identity());
        if(claim==null||claim.epoch!=epoch||!claim.region.equals(region)||claim.index!=baseline.index()
                ||claim.leaseEpoch!=baseline.leaseEpoch()||baseline.revision()<claim.revision)return;
        recoveries.computeIfAbsent(claim.identity,id->new PackageFreeNativeRecovery(region,epoch,baseline)).released(region,epoch,baseline);
    }
    /** Native teleport handler has already passed the client thread guard. */
    public static void recovered(net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket packet) {
        var claim=byEntity.get(packet.getId());if(claim==null||claim.level!=Minecraft.getInstance().level)return;
        var entity=claim.level.getEntity(packet.getId());if(entity==null||!entity.getUUID().equals(claim.uuid))return;
        var recovery=recoveries.get(claim.identity);if(recovery!=null)recovery.teleported(new PackageFreeNativeRecovery.Pose(
                packet.getX(),packet.getY(),packet.getZ(),packet.getyRot()*360f/256,packet.getxRot()*360f/256,packet.isOnGround()));
    }
    public static boolean restoreNativeRecovery(PackageEntity entity,ClientboundPackagePacket offer) {
        if(!matchesAuthority(entity,offer))return false;
        var recovery=recoveries.get(offer.baseline().identity());var pose=recovery==null?null:recovery.pose();if(pose==null)return false;
        entity.lerpTo(pose.x(),pose.y(),pose.z(),pose.yaw(),pose.pitch(),0);entity.setPos(pose.x(),pose.y(),pose.z());
        entity.xo=pose.x();entity.yo=pose.y();entity.zo=pose.z();entity.yRotO=pose.yaw();entity.setYRot(pose.yaw());entity.setOnGround(pose.ground());
        // Velocity comes from the native recovery motion handler. Do not replace it with
        // an older GPU sample, or modify the vanilla packet-position codec here.
        return true;
    }
    public static boolean renderedByGpu(PackageEntity entity) {
        Claim claim=byEntity.get(entity.getId());
        if(claim!=null && claim.level==entity.level() && claim.uuid.equals(entity.getUUID()))return true;
        NativeClaim nativeClaim=nativeClaims.get(entity.getId());
        return nativeClaim!=null&&nativeClaim.level==entity.level()&&nativeClaim.uuid.equals(entity.getUUID());
    }
    /** Visual observer identity never enters the authority/inventory identity map. */
    public static boolean authorityOwned(PackageEntity entity){var c=byEntity.get(entity.getId());return c!=null&&c.level==entity.level()&&c.uuid.equals(entity.getUUID());}
    public static boolean matchesAuthority(PackageEntity entity,ClientboundPackagePacket offer) {
        if(offer==null)return false;var c=byEntity.get(entity.getId());
        return c!=null&&c.level==entity.level()&&c.uuid.equals(entity.getUUID())&&c.entityId==offer.entityId()
                &&c.uuid.equals(offer.entityUuid())&&c.region.equals(offer.region())&&c.epoch==offer.epoch()
                &&c.identity.equals(offer.baseline().identity())&&c.index==offer.baseline().index()&&c.leaseEpoch==offer.baseline().leaseEpoch();
    }
    public static void claimNativeAfterAdmission(PackageEntity entity,long epoch,long id,long generation,int slotPlusOne) {
        if(!(entity.level() instanceof ClientLevel level)||level!=Minecraft.getInstance().level||entity.isRemoved()
                ||epoch<=0||id<=0||generation<=0||slotPlusOne<=0||authorityOwned(entity))throw new IllegalArgumentException("Native package render admission");
        nativeClaims.put(entity.getId(),new NativeClaim(level,entity.getId(),entity.getUUID(),epoch,id,generation));
    }
    public static void releaseNative(int entityId,UUID uuid,long epoch,long id,long generation) {
        var c=nativeClaims.get(entityId);if(c!=null&&c.uuid.equals(uuid)&&c.epoch==epoch&&c.id==id&&c.generation==generation)nativeClaims.remove(entityId,c);
    }
    public static void clearNative(long epoch){nativeClaims.entrySet().removeIf(e->e.getValue().epoch==epoch);}
    /** A terminal notice must match the exact owner epoch and identity; stale notices do nothing. */
    public static void released(PackageRegion region,long epoch,PackageAuthorityRegion.Baseline baseline) {
        if(baseline==null)return;
        Claim claim=byIdentity.get(baseline.identity());
        if(claim==null || claim.epoch!=epoch || !claim.region.equals(region)||claim.index!=baseline.index()||claim.leaseEpoch!=baseline.leaseEpoch())return;
        byEntity.remove(claim.entityId,claim);byIdentity.remove(claim.identity,claim);
        recoveries.remove(claim.identity);
    }
    public static boolean clear(){boolean hadClaims=!byEntity.isEmpty()||!nativeClaims.isEmpty();byEntity.clear();byIdentity.clear();nativeClaims.clear();recoveries.clear();return hadClaims;}
}
