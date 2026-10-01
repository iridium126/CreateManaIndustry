package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorPackage;
import net.minecraft.core.BlockPos;

/** Client implementation is installed by client bootstrap. Common mixins never link a client
 * class, use reflection, or perform world access from a background/render worker. */
public final class PackageChainClientHooks {
    public interface Listener {
        void beforeRead(ChainConveyorBlockEntity conveyor);
        void afterRead(ChainConveyorBlockEntity conveyor);
        void removed(ChainConveyorBlockEntity conveyor);
        void added(ChainConveyorBlockEntity conveyor,ChainConveyorPackage box,BlockPos connection);
    }
    private static Listener listener;
    private PackageChainClientHooks() {}
    public static void install(Listener next){listener=java.util.Objects.requireNonNull(next);}
    private static boolean client(ChainConveyorBlockEntity conveyor){return listener!=null && conveyor.getLevel()!=null && conveyor.getLevel().isClientSide;}
    public static void beforeRead(ChainConveyorBlockEntity c){if(client(c))listener.beforeRead(c);}
    public static void afterRead(ChainConveyorBlockEntity c){if(client(c))listener.afterRead(c);}
    public static void removed(ChainConveyorBlockEntity c){if(client(c))listener.removed(c);}
    public static void added(ChainConveyorBlockEntity c,ChainConveyorPackage b,BlockPos connection){if(client(c))listener.added(c,b,connection);}
}
