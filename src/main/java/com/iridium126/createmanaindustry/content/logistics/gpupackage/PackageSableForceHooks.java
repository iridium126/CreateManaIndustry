package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import com.simibubi.create.content.kinetics.fan.AirCurrent;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Shared typed probe for Create's server/client gameplay callbacks, never loaded without Sable. */
final class PackageSableForceHooks {
    static PackageForceHooks.FanProbe probe(AirCurrent current){
        var parent=Sable.HELPER.getContaining(current.source.getAirCurrentWorld(),current.source.getAirCurrentPos());
        if(parent==null)return null;
        return new PackageForceHooks.FanProbe(){
            @Override public AABB bounds(Entity entity){return new BoundingBox3d(entity.getBoundingBox()).transformInverse(parent.logicalPose(),new BoundingBox3d()).toMojang();}
            @Override public Vec3 position(Entity entity){return parent.logicalPose().transformPositionInverse(entity.position());}
        };
    }
    private PackageSableForceHooks(){}
}
