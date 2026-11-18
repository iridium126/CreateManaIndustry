package com.iridium126.createmanaindustry.client.particles.engine.spatial;

import java.nio.ByteBuffer;

/** Routes the common Storm tick and staged publication contract to the selected native module. */
record StormControlNative(boolean dxr) {
    void installStormTerrain(long worker, long revision, ByteBuffer rows, int count) {
        if (dxr) StormDxrNative.installStormTerrain(worker, revision, rows, count);
        else SpatialNative.installStormTerrain(worker, revision, rows, count);
    }

    boolean submitStormTick(long worker, ByteBuffer packet, int bytes, int slot, boolean timed) {
        return dxr ? StormDxrNative.submitStormTick(worker, packet, bytes, slot, timed) : SpatialNative.submitStormTick(worker, packet, bytes, slot, timed);
    }

    boolean prepareStormDisplaySlot(long worker, int slot) {
        return dxr ? StormDxrNative.prepareStormDisplaySlot(worker, slot) : SpatialNative.prepareStormDisplaySlot(worker, slot);
    }

    double[] stormTickTiming(long worker, long sequence) {
        return dxr ? StormDxrNative.stormTickTiming(worker, sequence) : SpatialNative.stormTickTiming(worker, sequence);
    }

    void delayStormControl(long worker, int milliseconds) {
        if (dxr) StormDxrNative.delayStormControl(worker, milliseconds);
        else SpatialNative.delayStormControl(worker, milliseconds);
    }

    int pollStormTick(long worker, long sequence) {
        return dxr ? StormDxrNative.pollStormTick(worker, sequence) : SpatialNative.pollStormTick(worker, sequence);
    }

    int consumeStormTick(long worker, long sequence) {
        return dxr ? StormDxrNative.consumeStormTick(worker, sequence) : SpatialNative.consumeStormTick(worker, sequence);
    }

    void installStormDome(long worker, float[] records, int[] keys, int[] flows) {
        if (dxr) StormDxrNative.installStormDome(worker, records, keys, flows);
        else SpatialNative.installStormDome(worker, records, keys, flows);
    }

    boolean copyStormDomeBatch(long worker, long sequence, ByteBuffer output) {
        return dxr ? StormDxrNative.copyStormDomeBatch(worker, sequence, output) : SpatialNative.copyStormDomeBatch(worker, sequence, output);
    }

    int copyStormFeatherImpacts(long worker, long sequence, ByteBuffer output) {
        return dxr ? StormDxrNative.copyStormFeatherImpacts(worker, sequence, output) : SpatialNative.copyStormFeatherImpacts(worker, sequence, output);
    }

    long stagedStormDisplayTime(long worker, int slot, long sequence, long[] metadata) {
        return dxr ? StormDxrNative.stagedStormDisplayTime(worker, slot, sequence, metadata) : SpatialNative.stagedStormDisplayTime(worker, slot, sequence, metadata);
    }

    boolean recycleStormDisplay(long worker, int slot, long sequence, long fence) {
        return dxr ? StormDxrNative.recycleStormDisplay(worker, slot, sequence, fence) : SpatialNative.recycleStormDisplay(worker, slot, sequence, fence);
    }

    String stormControlDiagnostics(long worker) {
        return dxr ? StormDxrNative.stormControlDiagnostics(worker) : SpatialNative.stormControlDiagnostics(worker);
    }

    boolean closeStormControl(long worker) {
        return dxr ? StormDxrNative.closeStormControl(worker) : SpatialNative.closeStormControl(worker);
    }
}
