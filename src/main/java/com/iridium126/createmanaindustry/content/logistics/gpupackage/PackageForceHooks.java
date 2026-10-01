package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import com.simibubi.create.content.kinetics.fan.AirCurrent;
import com.simibubi.create.content.logistics.box.PackageEntity;

/** Typed client installation; common/server mixins never resolve client implementations. */
public final class PackageForceHooks {
    public interface Listener {void fan(AirCurrent current);boolean owned(PackageEntity entity);boolean active();}
    private static Listener listener;
    public static void install(Listener next){listener=java.util.Objects.requireNonNull(next);}
    public static void fan(AirCurrent current){if(listener!=null&&current.source.getAirCurrentWorld()!=null&&current.source.getAirCurrentWorld().isClientSide)listener.fan(current);}
    public static boolean simulated(PackageEntity entity){return entity.level().isClientSide?
            listener!=null&&listener.owned(entity):PackageAuthorityManager.simulated(entity);}
    public static boolean active(net.minecraft.world.level.Level level){return level!=null&&(level.isClientSide?
            listener!=null&&listener.active():level instanceof net.minecraft.server.level.ServerLevel server&&PackageAuthorityManager.hasSimulated(server));}
    public interface FanProbe {net.minecraft.world.phys.AABB bounds(net.minecraft.world.entity.Entity entity);net.minecraft.world.phys.Vec3 position(net.minecraft.world.entity.Entity entity);}
    public static FanProbe fanProbe(AirCurrent current){return fanProbe(net.neoforged.fml.ModList.get().isLoaded("sable"),current);}
    public static FanProbe fanProbe(boolean loaded,AirCurrent current){return loaded?PackageSableForceHooks.probe(current):null;}
    private PackageForceHooks(){}
}
