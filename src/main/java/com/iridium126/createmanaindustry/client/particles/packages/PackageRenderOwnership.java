package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.concurrent.ConcurrentHashMap;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageAuthorityRegion;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageLease;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageRegion;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ClientboundPackagePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/** Record display arbitration after a matching visible GPU admission. */
public final class PackageRenderOwnership {
    private record Claim(ClientLevel level,PackageRegion region,long epoch,PackageLease.Identity identity,int index,long leaseEpoch) {}
    private static final ConcurrentHashMap<PackageLease.Identity,Claim> claims=new ConcurrentHashMap<>();
    private PackageRenderOwnership() {}
    public static void claimLightAfterAdmission(ClientboundPackagePacket offer,ClientboundPackagePacket active,int slotPlusOne) {
        var level=Minecraft.getInstance().level;
        if(level==null || offer.action()!=ClientboundPackagePacket.OFFER || active.action()!=ClientboundPackagePacket.ACTIVE
                || slotPlusOne<=0 || !level.dimension().location().equals(offer.dimension())
                || !offer.dimension().equals(active.dimension()) || !offer.region().equals(active.region())
                || offer.epoch()!=active.epoch() || offer.regionRevision()!=active.regionRevision()
                || offer.baseline().index()!=active.baseline().index()
                || !offer.baseline().identity().equals(active.baseline().identity())
                || offer.baseline().leaseEpoch()!=active.baseline().leaseEpoch())
            throw new IllegalArgumentException("Package light admission");
        var b=active.baseline();claims.put(b.identity(),new Claim(level,active.region(),active.epoch(),b.identity(),b.index(),b.leaseEpoch()));
    }
    public static boolean hasIdentity(PackageLease.Identity identity){var c=claims.get(identity);return c!=null&&c.level==Minecraft.getInstance().level;}
    public static void released(PackageRegion region,long epoch,PackageAuthorityRegion.Baseline baseline) {
        if(baseline==null)return;var c=claims.get(baseline.identity());
        if(c!=null&&c.epoch==epoch&&c.region.equals(region)&&c.index==baseline.index()&&c.leaseEpoch==baseline.leaseEpoch())claims.remove(c.identity,c);
    }
    public static boolean clear(){boolean hadClaims=!claims.isEmpty();claims.clear();return hadClaims;}
}
