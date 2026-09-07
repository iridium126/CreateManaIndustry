package com.iridium126.createmanaindustry.client.dimension.render;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;

import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;

/**
 * Session owner for ALLVR near-render state.
 *
 * <p>This class deliberately contains no GL objects.  It is the authoritative
 * home for the level epoch and cell content revisions; the renderer may keep a
 * separate GPU allocation, but it must ask this object whether a result is
 * still current before publishing it.
 */
public final class AllvrRenderWorld implements AutoCloseable {

    private final Long2ObjectOpenHashMap<AllvrRenderCell> cells = new Long2ObjectOpenHashMap<>();
    private ClientLevel level;
    private long epoch = 1L;
    private long resourceRevision = 1L;

    public void bind(ClientLevel level) {
        if (this.level == level) {
            return;
        }
        this.level = level;
        this.beginSession();
    }

    public ClientLevel level() {
        return this.level;
    }

    public long epoch() {
        return this.epoch;
    }

    public long resourceRevision() {
        return this.resourceRevision;
    }

    public AllvrRenderCell ensureCell(long key) {
        return this.cells.computeIfAbsent(key, AllvrRenderCell::new);
    }

    public AllvrRenderCell cell(long key) {
        return this.cells.get(key);
    }

    public int cellCount() {
        return this.cells.size();
    }

    public List<AllvrRenderCell> cellsSnapshot() {
        return new ArrayList<>(this.cells.values());
    }

    public long markCellDirty(long key) {
        return this.ensureCell(key).markDirty();
    }

    public void forgetCell(long key) {
        this.cells.remove(key);
    }

    /** Removes all eight render cells owned by one streamed cube. */
    public void forgetCube(long cubeKey) {
        AllvrCubePos cube = AllvrCubePos.fromLong(cubeKey);
        for (int y = 0; y < 2; y++) {
            for (int z = 0; z < 2; z++) {
                for (int x = 0; x < 2; x++) {
                    this.forgetCell(AllvrRenderCellKey.ofCell((cube.getX() << 1) + x,
                        (cube.getY() << 1) + y, (cube.getZ() << 1) + z));
                }
            }
        }
    }

    /** Starts a new level session and invalidates every old result. */
    public void beginSession() {
        this.epoch++;
        this.cells.clear();
        this.resourceRevision++;
    }

    /** Resource reload invalidates model/material snapshots without changing level identity. */
    public long bumpResourceRevision() {
        return ++this.resourceRevision;
    }

    @Override
    public void close() {
        this.beginSession();
        this.level = null;
    }
}
