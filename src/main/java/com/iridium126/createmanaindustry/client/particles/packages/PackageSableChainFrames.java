package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.UUID;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageChainGpuFrame;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageChainSpace;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/** Direct compileOnly Sable render/logical API. No reflection or background world access. */
final class PackageSableChainFrames implements PackageChainFrameScene.Bridge {
    @Override public PackageChainFrameScene.Parent find(ClientLevel level,BlockPos conveyor) {
        var containing=Sable.HELPER.getContaining(level,conveyor);
        if(!(containing instanceof ClientSubLevel parent)||parent.isRemoved())return null;
        UUID id=parent.getUniqueId();
        return new PackageChainFrameScene.Parent() {
            @Override public UUID id(){return id;}
            @Override public boolean valid(){return !parent.isRemoved()&&id.equals(parent.getUniqueId())&&Sable.HELPER.getContaining(level,conveyor)==parent;}
            @Override public PackageChainGpuFrame capture(Vec3 origin) {
                // Copy the render pose before asking for logical; SDK poses are mutable and
                // parent.renderPose() follows Sable's own Minecraft partial-tick cache. The
                // scene already validated membership this frame; avoid a second world lookup.
                return new PackageChainGpuFrame(frame(id,origin,parent.renderPose()),frame(id,origin,parent.logicalPose()));
            }
        };
    }
    static PackageChainSpace.Frame frame(UUID id,Vec3 origin,Pose3dc pose) {
        var p=PackageSablePose.capture(pose,origin.x,origin.y,origin.z,new Vector3d());
        return new PackageChainSpace.Frame(id,origin,new Vec3(p.tx(),p.ty(),p.tz()),
                new Vec3(p.xx(),p.xy(),p.xz()),new Vec3(p.yx(),p.yy(),p.yz()),new Vec3(p.zx(),p.zy(),p.zz()));
    }
}
