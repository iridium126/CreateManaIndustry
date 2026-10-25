package com.iridium126.createmanaindustry.dimension.net;

import java.util.ArrayDeque;
import java.util.List;

/** Main-thread per-watch ordering barrier, independent of Minecraft for regression tests. */
public final class AllvrStreamState<T> {
    public static final int MAX_BUFFERED_UPDATES = 4096;
    private long generation = -1;
    private long revision = -1;
    private boolean decoding;
    private boolean resync;
    private final ArrayDeque<Entry<T>> updates = new ArrayDeque<>();
    private record Entry<T>(long revision, T value) {}

    public boolean begin(long generation, long revision) {
        if (generation < this.generation || (generation == this.generation && revision < this.revision)) return false;
        if (generation != this.generation) updates.clear();
        this.generation = generation;
        this.revision = revision;
        this.decoding = true;
        this.resync = false;
        updates.removeIf(update -> update.revision <= revision);
        return true;
    }
    public boolean accepts(long generation) { return generation == this.generation; }
    public boolean update(long generation, long revision, T value, java.util.function.Consumer<T> apply) {
        if (!accepts(generation) || revision <= this.revision || resync) return false;
        if (decoding) {
            if (updates.size() >= MAX_BUFFERED_UPDATES) { fail(); return false; }
            updates.addLast(new Entry<>(revision, value));
            return true;
        }
        if (revision != this.revision + 1) { fail(); return false; }
        apply.accept(value);
        this.revision = revision;
        return true;
    }
    public boolean canComplete(long generation, long revision) {
        return accepts(generation) && revision == this.revision && !resync && decoding;
    }
    public boolean complete(long generation, long revision, java.util.function.Consumer<T> apply) {
        if (!canComplete(generation, revision)) return false;
        decoding = false;
        var pending = List.copyOf(updates);
        updates.clear();
        for (Entry<T> update : pending) update(generation, update.revision, update.value, apply);
        return !resync;
    }
    public void forget(long generation) {
        if (generation >= this.generation) {
            this.generation = generation;
            revision = Long.MAX_VALUE;
            updates.clear();
            decoding = false;
            resync = false;
        }
    }
    public void fail() { updates.clear(); resync = true; decoding = false; }
    public boolean needsResync() { return resync; }
    public long generation() { return generation; }
    public boolean isDecoding() { return decoding; }
}
