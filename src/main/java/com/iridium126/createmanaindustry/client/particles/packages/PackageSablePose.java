package com.iridium126.createmanaindustry.client.particles.packages;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import org.joml.Vector3d;

/** Sable's rotation point and scale semantics, captured without world-coordinate differencing. */
final class PackageSablePose {
    private PackageSablePose() {}

    static PackageMovingGeometry.Pose capture(Pose3dc pose, int ox, int oy, int oz, Vector3d scratch) {
        pose.transformNormal(scratch.set(1, 0, 0), scratch);
        double xx = scratch.x, xy = scratch.y, xz = scratch.z;
        pose.transformNormal(scratch.set(0, 1, 0), scratch);
        double yx = scratch.x, yy = scratch.y, yz = scratch.z;
        pose.transformNormal(scratch.set(0, 0, 1), scratch);
        double zx = scratch.x, zy = scratch.y, zz = scratch.z;
        pose.transformPosition(scratch.set(ox, oy, oz), scratch);
        return new PackageMovingGeometry.Pose(xx, xy, xz, yx, yy, yz, zx, zy, zz, scratch.x, scratch.y, scratch.z);
    }
}
