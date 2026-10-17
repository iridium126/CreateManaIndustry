package com.iridium126.createmanaindustry.client.hex.pattern;
/** All backing storage, including retired/in-flight buffers, is charged exactly once. */
public final class HexGpuBudget {
    private final long limit;
    private long used;
    public HexGpuBudget(long limit) { if(limit<1)throw new IllegalArgumentException("Hex GPU budget");this.limit=limit; }
    public boolean reserve(long bytes) { if(bytes<0)throw new IllegalArgumentException("Negative allocation");if(bytes>limit-used)return false;used+=bytes;return true; }
    public void release(long bytes) { if(bytes<0||bytes>used)throw new IllegalStateException("Unbalanced GPU allocation");used-=bytes; }
    public long used(){return used;}public long limit(){return limit;}
}
