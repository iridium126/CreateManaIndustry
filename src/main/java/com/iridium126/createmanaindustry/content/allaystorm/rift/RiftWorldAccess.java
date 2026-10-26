package com.iridium126.createmanaindustry.content.allaystorm.rift;
/** Scoped access for snapshot/rebuild work while external block-entity access is isolated. */
public final class RiftWorldAccess implements AutoCloseable {
    private static final ThreadLocal<Integer> DEPTH=ThreadLocal.withInitial(()->0);
    private final int previous;
    private RiftWorldAccess(boolean allowed){previous=DEPTH.get();DEPTH.set(allowed?previous+1:0);}
    public static RiftWorldAccess open(){return new RiftWorldAccess(true);}
    public static RiftWorldAccess block(){return new RiftWorldAccess(false);}
    public static boolean allowed(){return DEPTH.get()>0;}
    public void close(){if(previous==0)DEPTH.remove();else DEPTH.set(previous);}
}
