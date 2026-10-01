package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Immutable conveyor-local/world coordinate boundary. Routes, progress and native snapshots
 * stay in Create's coordinate space. Only authority interest and world interactions project.
 * One frame per track check/interaction, never one transform per active package per tick. */
public final class PackageChainSpace {
    public interface Bridge { Frame capture(Level level,BlockPos conveyor); }
    public record Frame(UUID parent,Vec3 localOrigin,Vec3 worldOrigin,Vec3 x,Vec3 y,Vec3 z) {
        public Frame {
            if(!finite(localOrigin)||!finite(worldOrigin)||!finite(x)||!finite(y)||!finite(z))
                throw new IllegalArgumentException("Non-finite chain frame");
            double a=x.lengthSqr(),b=y.lengthSqr(),c=z.lengthSqr();
            if(a<1e-8||b<1e-8||c<1e-8||a>1e6||b>1e6||c>1e6
                    ||Math.abs(x.dot(y))>1e-6*Math.sqrt(a*b)
                    ||Math.abs(x.dot(z))>1e-6*Math.sqrt(a*c)
                    ||Math.abs(y.dot(z))>1e-6*Math.sqrt(b*c)||x.dot(y.cross(z))<=0)
                throw new IllegalArgumentException("Chain frame must have orthogonal positive scales");
        }
        public Vec3 world(Vec3 local) {
            var d=local.subtract(localOrigin);
            return new Vec3(worldOrigin.x+x.x*d.x+y.x*d.y+z.x*d.z,
                    worldOrigin.y+x.y*d.x+y.y*d.y+z.y*d.z,
                    worldOrigin.z+x.z*d.x+y.z*d.y+z.z*d.z);
        }
        public Vec3 local(Vec3 world) {
            var d=world.subtract(worldOrigin);
            return new Vec3(localOrigin.x+d.dot(x)/x.lengthSqr(),localOrigin.y+d.dot(y)/y.lengthSqr(),localOrigin.z+d.dot(z)/z.lengthSqr());
        }
        public PackageRegion region(){return PackageRegion.at(new PackageLease.Pose(worldOrigin.x,worldOrigin.y,worldOrigin.z,0,0,0,0));}
        /** Exact oriented/scaled local box test. Projecting its world AABB would admit false
         * hits at rotated corners. The world ray's length/reach has already been determined. */
        public boolean hits(AABB localBounds,Vec3 from,Vec3 to){return localBounds.clip(local(from),local(to)).isPresent();}
    }
    public static Frame stationary(BlockPos conveyor){var origin=Vec3.atCenterOf(conveyor);return new Frame(null,origin,origin,new Vec3(1,0,0),new Vec3(0,1,0),new Vec3(0,0,1));}
    /** Runtime presence gate is outside the typed optional class, including dedicated servers. */
    public static Bridge optionalBridge(boolean loaded){return loaded?new PackageSableChainSpace():null;}
    private static final class Optional {static final Bridge BRIDGE=optionalBridge(net.neoforged.fml.ModList.get().isLoaded("sable"));}
    public static Frame capture(Level level,BlockPos conveyor) {
        if(level==null)return null;
        return Optional.BRIDGE==null?stationary(conveyor):Optional.BRIDGE.capture(level,conveyor);
    }
    private static boolean finite(Vec3 v){return v!=null&&Double.isFinite(v.x)&&Double.isFinite(v.y)&&Double.isFinite(v.z);}
    private PackageChainSpace(){}
}
