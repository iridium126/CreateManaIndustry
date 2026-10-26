package com.iridium126.createmanaindustry.content.allaystorm.rift;

import com.iridium126.createmanaindustry.content.allaystorm.AllayStormData;
import com.iridium126.createmanaindustry.content.allaystorm.StormIdentity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** Common entry point; optional transport implementations are selected lazily. */
public final class StormRiftTransfers {
    interface Controller {
        boolean frozen(UUID entity);
        void ready(ServerPlayer player, UUID transaction);
        void consider(ServerLevel source, AllayStormData storm, double x, double z, ServerLevel target);
        void cancelSource(ServerLevel source, StormIdentity storm);
        default boolean together(UUID entity,UUID player){return false;}
        default boolean retained(UUID entity){return frozen(entity);}
        default boolean staging(ServerLevel level,UUID structure){return false;}
        default String report(){return "rift-transfer=active";}
    }
    private static Controller controller;
    private StormRiftTransfers() {}
    static void install(Controller implementation) {
        if(controller!=null)throw new IllegalStateException("A rift transfer coordinator is already installed");
        controller=Objects.requireNonNull(implementation);
    }
    static void clear(){controller=null;}
    public static boolean together(UUID entity,UUID player){return controller!=null&&controller.together(entity,player);}
    public static boolean retained(UUID entity){return controller!=null&&controller.retained(entity);}
    public static boolean staging(ServerLevel level,UUID structure){return controller!=null&&controller.staging(level,structure);}
    public static boolean frozen(UUID entity) { return controller!=null&&controller.frozen(entity); }
    public static void ready(ServerPlayer player, UUID transaction) {
        // An unsolicited acknowledgement is inert. It cannot collect a group or create a transaction.
        if(controller!=null)controller.ready(player, transaction);
    }
    public static void consider(ServerLevel source, AllayStormData storm, double x, double z, ServerLevel target) {
        if(controller!=null)controller.consider(source,storm,x,z,target);
    }
    public static void cancelSource(ServerLevel source, StormIdentity storm) { if(controller!=null)controller.cancelSource(source,storm); }
    public static String report() { return controller==null?"rift-transfer=not released (server not running)":controller.report(); }
}
