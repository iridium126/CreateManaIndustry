package com.iridium126.createmanaindustry.content.allaystorm.rift.sable;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import java.util.*;
/** Native plot codec, restricted to a single chunk per budgeted snapshot step. */
public final class RiftPlotSnapshot implements AutoCloseable {
    private static final ThreadLocal<PlotChunkHolder> CHUNK=new ThreadLocal<>();
    public RiftPlotSnapshot(PlotChunkHolder chunk){if(CHUNK.get()!=null)throw new IllegalStateException("Nested plot snapshot");CHUNK.set(chunk);}
    public static Collection<PlotChunkHolder> chunks(Collection<PlotChunkHolder> original){var chunk=CHUNK.get();return chunk==null?original:List.of(chunk);}
    public void close(){CHUNK.remove();}
}
