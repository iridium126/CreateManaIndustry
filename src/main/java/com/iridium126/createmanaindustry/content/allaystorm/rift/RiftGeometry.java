package com.iridium126.createmanaindustry.content.allaystorm.rift;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.function.UnaryOperator;

public final class RiftGeometry {
    private RiftGeometry(){}
    /** A conservative bound of each actual collision cuboid, not a bound of the entire empty structure. */
    public static AABB transform(AABB box,UnaryOperator<Vec3> transform) {
        double x0=Double.POSITIVE_INFINITY,y0=x0,z0=x0,x1=Double.NEGATIVE_INFINITY,y1=x1,z1=x1;
        for(int corner=0;corner<8;corner++) {
            Vec3 point=transform.apply(new Vec3((corner&1)==0?box.minX:box.maxX,(corner&2)==0?box.minY:box.maxY,(corner&4)==0?box.minZ:box.maxZ));
            x0=Math.min(x0,point.x);y0=Math.min(y0,point.y);z0=Math.min(z0,point.z);x1=Math.max(x1,point.x);y1=Math.max(y1,point.y);z1=Math.max(z1,point.z);
        }
        return new AABB(x0,y0,z0,x1,y1,z1);
    }
}
