package com.iridium126.createmanaindustry.client.particles.packages;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Validates one GPU-selected package against the current camera ray; it never scans the pool. */
final class PackageFreeHoverPick {
    private PackageFreeHoverPick() {}

    static Vec3 intersection(PackagePoseQueryGpu.Ray ray,double ox,double oy,double oz,
                             float centerX,float centerY,float centerZ,float halfWidth,float halfHeight) {
        if(ray==null||!Double.isFinite(ox)||!Double.isFinite(oy)||!Double.isFinite(oz)
                ||!Float.isFinite(centerX)||!Float.isFinite(centerY)||!Float.isFinite(centerZ)
                ||!Float.isFinite(halfWidth)||!Float.isFinite(halfHeight)||halfWidth<=0||halfHeight<=0)return null;
        var from=new Vec3(ox+ray.x(),oy+ray.y(),oz+ray.z());
        var to=from.add(ray.dx(),ray.dy(),ray.dz());
        var box=new AABB(ox+centerX-halfWidth,oy+centerY-halfHeight,oz+centerZ-halfWidth,
                ox+centerX+halfWidth,oy+centerY+halfHeight,oz+centerZ+halfWidth);
        if(box.contains(from))return from;
        return box.clip(from,to).orElse(null);
    }
}
