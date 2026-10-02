package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import com.simibubi.create.content.kinetics.fan.AirCurrent;

/** Typed client installation; common/server hooks never resolve client implementations. */
public final class PackageForceHooks {
    public interface Listener {void fan(AirCurrent current);}
    private static Listener listener;
    public static void install(Listener next){listener=java.util.Objects.requireNonNull(next);}
    public static void fan(AirCurrent current){if(listener!=null&&current.source.getAirCurrentWorld()!=null&&current.source.getAirCurrentWorld().isClientSide)listener.fan(current);}
    private PackageForceHooks(){}
}
