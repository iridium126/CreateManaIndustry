package com.iridium126.createmanaindustry.client.particles.packages;

import com.simibubi.create.content.kinetics.fan.AirCurrent;
import com.simibubi.create.content.kinetics.fan.NozzleBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.world.entity.Entity;
import org.joml.Vector3d;

/** Direct compileOnly Sable adapter. Only owner-thread primitive copies escape to the worker. */
final class PackageSableForceSources implements PackageForceClient.OptionalBridge {
    private final Vector3d scratch=new Vector3d();
    private PackageForceScene.Source framed(PackageForceScene.Source source,dev.ryanhcode.sable.sublevel.SubLevel parent){
        if(parent==null)return source;
        if(!(parent instanceof ClientSubLevel client)||client.isRemoved()||!client.isFinalized())
            throw new IllegalStateException("Force source sublevel not finalized/alive");
        var pose=PackageSablePose.capture(client.logicalPose(),source.x(),source.y(),source.z(),scratch);
        return source.framed(new PackageForceScene.Frame(pose,source.x(),source.y(),source.z()));
    }
    @Override public PackageForceScene.Source entity(PackageForceScene.Source source,Entity entity){return framed(source,Sable.HELPER.getContaining(entity));}
    @Override public PackageForceScene.Source fan(PackageForceScene.Source source,AirCurrent fan){return framed(source,Sable.HELPER.getContaining(fan.source.getAirCurrentWorld(),fan.source.getAirCurrentPos()));}
    @Override public PackageForceScene.Source nozzle(PackageForceScene.Source source,NozzleBlockEntity nozzle){return framed(source,Sable.HELPER.getContaining(nozzle.getLevel(),nozzle.getBlockPos()));}
}
