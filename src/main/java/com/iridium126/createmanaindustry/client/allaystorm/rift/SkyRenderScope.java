package com.iridium126.createmanaindustry.client.allaystorm.rift;

import com.iridium126.createmanaindustry.content.allaystorm.rift.AllaySkyContext;

/** Read-only render scope; never mutates the ClientLevel or its normal time synchronization. */
public final class SkyRenderScope {
    private static AllaySkyContext target;
    private static long elapsed;
    private static Thread owner;
    private SkyRenderScope() {}
    public static boolean active(){return target!=null&&owner==Thread.currentThread();}
    public static long dayTime(){return target.dayTime()+(target.daylightCycle()?(long)Math.floor(target.fraction()+elapsed*(target.perTick()<0?1:target.perTick())):0);}
    public static float rain(){return target.rain();}
    public static float thunder(){return target.thunder();}
    public static void enter(AllaySkyContext context,long ticks){if(target!=null)throw new IllegalStateException("Nested target sky");owner=Thread.currentThread();target=context;elapsed=ticks;}
    public static void leave(){target=null;elapsed=0;owner=null;}
}
