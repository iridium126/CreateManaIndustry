package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/** Per-track sidecar, not a particle/header ABI change. Three render rows followed by three
 * logical rows, translations relative to the common world physics origin. Both poses share
 * the exact native origin/parent; native chain bodies use that origin and remain local. */
public record PackageChainGpuFrame(PackageChainSpace.Frame render,PackageChainSpace.Frame logical) {
    public static final int BYTES=96;
    public PackageChainGpuFrame {
        Objects.requireNonNull(render);Objects.requireNonNull(logical);
        if(!Objects.equals(render.parent(),logical.parent())||!render.localOrigin().equals(logical.localOrigin()))
            throw new IllegalArgumentException("Chain frame namespace/origin mismatch");
    }
    public void write(ByteBuffer out,double ox,double oy,double oz) {
        if(out==null||!out.isDirect()||out.isReadOnly()||out.remaining()!=BYTES)
            throw new IllegalArgumentException("Chain frame output layout");
        // Frame already validates the bounded axes; validate both translations before any
        // output mutation. No per-track float/Frame arrays in the upload hot path.
        validateOrigin(render,ox,oy,oz);validateOrigin(logical,ox,oy,oz);
        var b=out.duplicate().order(ByteOrder.nativeOrder());
        writeFrame(b,render,ox,oy,oz);writeFrame(b,logical,ox,oy,oz);
    }
    private static void validateOrigin(PackageChainSpace.Frame f,double ox,double oy,double oz) {
        if(!Float.isFinite((float)(f.worldOrigin().x-ox))||!Float.isFinite((float)(f.worldOrigin().y-oy))
                ||!Float.isFinite((float)(f.worldOrigin().z-oz)))throw new IllegalArgumentException("Chain frame needs a finite local world origin");
    }
    private static void writeFrame(ByteBuffer b,PackageChainSpace.Frame f,double ox,double oy,double oz) {
        b.putFloat((float)f.x().x).putFloat((float)f.y().x).putFloat((float)f.z().x).putFloat((float)(f.worldOrigin().x-ox));
        b.putFloat((float)f.x().y).putFloat((float)f.y().y).putFloat((float)f.z().y).putFloat((float)(f.worldOrigin().y-oy));
        b.putFloat((float)f.x().z).putFloat((float)f.y().z).putFloat((float)f.z().z).putFloat((float)(f.worldOrigin().z-oz));
    }
}
