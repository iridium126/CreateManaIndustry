package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import com.simibubi.create.content.kinetics.fan.AirCurrent;
import com.simibubi.create.content.kinetics.fan.NozzleBlockEntity;

/** Typed client installation; common/server hooks never resolve client implementations. */
public final class PackageForceHooks {
    public interface Listener {void fan(AirCurrent current);void nozzle(NozzleBlockEntity nozzle,float range,boolean pushing);}
    private static Listener listener;
    public static void install(Listener next){listener=java.util.Objects.requireNonNull(next);}
    public static void fan(AirCurrent current){if(listener!=null&&current.source.getAirCurrentWorld()!=null&&current.source.getAirCurrentWorld().isClientSide)listener.fan(current);}
    public static void nozzle(NozzleBlockEntity nozzle,float range,boolean pushing){if(listener!=null&&nozzle.getLevel()!=null&&nozzle.getLevel().isClientSide)listener.nozzle(nozzle,range,pushing);}
    private PackageForceHooks(){}
}
