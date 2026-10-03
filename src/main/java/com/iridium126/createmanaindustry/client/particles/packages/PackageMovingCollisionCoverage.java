package com.iridium126.createmanaindustry.client.particles.packages;

import net.minecraft.world.phys.AABB;

/** Conservative world-space broad phase shared by moving-geometry admission checks. */
final class PackageMovingCollisionCoverage {
    private PackageMovingCollisionCoverage() {}

    /**
     * Bounds the complete pose interval, including intermediate rotation. A sphere around
     * the captured origin is intentionally conservative and matches the GPU coarse phase.
     */
    static AABB sweptBounds(PackageMovingGeometry.Bounds local,
                            PackageMovingGeometry.Pose previous,
                            PackageMovingGeometry.Pose current) {
        if (sameRotation(previous, current)) {
            AABB a = transformed(local, previous);
            AABB b = transformed(local, current);
            return new AABB(Math.min(a.minX, b.minX) - 1.01, Math.min(a.minY, b.minY) - 1.01,
                    Math.min(a.minZ, b.minZ) - 1.01, Math.max(a.maxX, b.maxX) + 1.01,
                    Math.max(a.maxY, b.maxY) + 1.01, Math.max(a.maxZ, b.maxZ) + 1.01);
        }
        double x = Math.max(Math.abs(local.x0()), Math.abs(local.x1()));
        double y = Math.max(Math.abs(local.y0()), Math.abs(local.y1()));
        double z = Math.max(Math.abs(local.z0()), Math.abs(local.z1()));
        double radius = Math.sqrt(x * x + y * y + z * z)
                * Math.max(maxScale(previous), maxScale(current)) + 1.01;
        double minX = Math.min(previous.tx(), current.tx()) - radius;
        double minY = Math.min(previous.ty(), current.ty()) - radius;
        double minZ = Math.min(previous.tz(), current.tz()) - radius;
        double maxX = Math.max(previous.tx(), current.tx()) + radius;
        double maxY = Math.max(previous.ty(), current.ty()) + radius;
        double maxZ = Math.max(previous.tz(), current.tz()) + radius;
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static boolean sameRotation(PackageMovingGeometry.Pose a, PackageMovingGeometry.Pose b) {
        return sameColumn(a.xx(), a.xy(), a.xz(), b.xx(), b.xy(), b.xz())
                && sameColumn(a.yx(), a.yy(), a.yz(), b.yx(), b.yy(), b.yz())
                && sameColumn(a.zx(), a.zy(), a.zz(), b.zx(), b.zy(), b.zz());
    }

    private static boolean sameColumn(double ax, double ay, double az, double bx, double by, double bz) {
        double as = Math.sqrt(ax * ax + ay * ay + az * az), bs = Math.sqrt(bx * bx + by * by + bz * bz);
        return Math.abs(ax / as - bx / bs) <= 1e-6 && Math.abs(ay / as - by / bs) <= 1e-6
                && Math.abs(az / as - bz / bs) <= 1e-6;
    }

    private static AABB transformed(PackageMovingGeometry.Bounds bounds, PackageMovingGeometry.Pose pose) {
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (int corner = 0; corner < 8; corner++) {
            double x = (corner & 1) == 0 ? bounds.x0() : bounds.x1();
            double y = (corner & 2) == 0 ? bounds.y0() : bounds.y1();
            double z = (corner & 4) == 0 ? bounds.z0() : bounds.z1();
            double px = pose.xx() * x + pose.yx() * y + pose.zx() * z + pose.tx();
            double py = pose.xy() * x + pose.yy() * y + pose.zy() * z + pose.ty();
            double pz = pose.xz() * x + pose.yz() * y + pose.zz() * z + pose.tz();
            minX = Math.min(minX, px); minY = Math.min(minY, py); minZ = Math.min(minZ, pz);
            maxX = Math.max(maxX, px); maxY = Math.max(maxY, py); maxZ = Math.max(maxZ, pz);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    static boolean intersects(PackageMovingGeometry.Bounds local,
                              PackageMovingGeometry.Pose previous,
                              PackageMovingGeometry.Pose current,
                              AABB query) {
        AABB source = sweptBounds(local, previous, current);
        return source.maxX >= query.minX && source.minX <= query.maxX
                && source.maxY >= query.minY && source.minY <= query.maxY
                && source.maxZ >= query.minZ && source.minZ <= query.maxZ;
    }

    private static double maxScale(PackageMovingGeometry.Pose pose) {
        double x = Math.sqrt(pose.xx() * pose.xx() + pose.xy() * pose.xy() + pose.xz() * pose.xz());
        double y = Math.sqrt(pose.yx() * pose.yx() + pose.yy() * pose.yy() + pose.yz() * pose.yz());
        double z = Math.sqrt(pose.zx() * pose.zx() + pose.zy() * pose.zy() + pose.zz() * pose.zz());
        return Math.max(x, Math.max(y, z));
    }
}
