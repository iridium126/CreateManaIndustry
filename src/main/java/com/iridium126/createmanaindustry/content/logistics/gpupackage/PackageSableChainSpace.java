package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import java.util.UUID;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/** Direct compileOnly Sable API, invoked on the owning world thread only. No reflection,
 * client types, mutable sublevel/pose or helper object escapes the immutable frame. */
final class PackageSableChainSpace implements PackageChainSpace.Bridge {
    @Override public PackageChainSpace.Frame capture(Level level,BlockPos conveyor) {
        var parent=Sable.HELPER.getContaining(level,conveyor);
        if(parent==null)return PackageChainSpace.stationary(conveyor);
        if(parent.isRemoved())return null;
        return capture(parent.getUniqueId(),Vec3.atCenterOf(conveyor),parent.logicalPose());
    }
    static PackageChainSpace.Frame capture(UUID id,Vec3 origin,Pose3dc pose) {
        var scratch=new Vector3d();
        pose.transformNormal(scratch.set(1,0,0),scratch);var x=copy(scratch);
        pose.transformNormal(scratch.set(0,1,0),scratch);var y=copy(scratch);
        pose.transformNormal(scratch.set(0,0,1),scratch);var z=copy(scratch);
        pose.transformPosition(scratch.set(origin.x,origin.y,origin.z),scratch);
        return new PackageChainSpace.Frame(id,origin,copy(scratch),x,y,z);
    }
    private static Vec3 copy(Vector3d vector){return new Vec3(vector.x,vector.y,vector.z);}
}
