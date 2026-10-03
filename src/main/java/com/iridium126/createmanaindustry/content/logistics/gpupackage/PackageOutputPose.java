package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** One-time machine output admission. No tick motion or world collision traversal. */
public final class PackageOutputPose {
    private static final double CLEARANCE=1.0/1024;
    private PackageOutputPose() {}

    /** Create's dropped-item conversion multiplies tick velocity by 1.5. */
    public static PackageLease.Pose dropped(Vec3 position,Vec3 tickVelocity,float yaw) {
        return new PackageLease.Pose(position.x,position.y,position.z,
                (float)(tickVelocity.x*30),(float)(tickVelocity.y*30),(float)(tickVelocity.z*30),yaw);
    }

    /** Keep an already separated output unchanged; otherwise exit the source through its outlet.
     * Feet, rather than collider centres, are the durable record's position convention. */
    public static PackageLease.Pose clearSource(PackageLease.Pose pose,float width,float height,AABB source,Direction outlet) {
        double r=width*.5,x=pose.x(),y=pose.y(),z=pose.z();
        var body=new AABB(x-r,y,z-r,x+r,y+height,z+r);
        if(!body.intersects(source))return pose;
        switch(outlet) {
            case DOWN -> y=Math.min(y,source.minY-height-CLEARANCE);
            case UP -> y=Math.max(y,source.maxY+CLEARANCE);
            case WEST -> x=Math.min(x,source.minX-r-CLEARANCE);
            case EAST -> x=Math.max(x,source.maxX+r+CLEARANCE);
            case NORTH -> z=Math.min(z,source.minZ-r-CLEARANCE);
            case SOUTH -> z=Math.max(z,source.maxZ+r+CLEARANCE);
        }
        return new PackageLease.Pose(x,y,z,pose.vx(),pose.vy(),pose.vz(),pose.yaw());
    }
}
