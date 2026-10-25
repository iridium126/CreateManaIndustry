package com.iridium126.createmanaindustry.dimension.cube;

import java.util.*;
import java.util.function.*;

/** Incremental residency maintenance. A failed/noSave snapshot never removes its cube. */
public final class AllvrUnloadQueue {
    private record Candidate(long key, long due) {}
    private final Set<Long> queued = new HashSet<>();
    private final PriorityQueue<Candidate> candidates = new PriorityQueue<>(Comparator.comparingLong(Candidate::due));
    private long[] scan = new long[0];
    private int cursor;
    public void request(long[] keys) { if (cursor >= scan.length) { scan = keys; cursor = 0; } }
    public void tick(long now, LongPredicate needed, LongToIntFunction unload) {
        long deadline = System.nanoTime() + 1_000_000L;
        int inspected = 0;
        while (cursor < scan.length && inspected++ < 256 && System.nanoTime() < deadline) {
            long key = scan[cursor++];
            if (!needed.test(key) && queued.add(key)) candidates.add(new Candidate(key, now));
        }
        if (cursor == scan.length) { scan = new long[0]; cursor = 0; }
        int processed = 0;
        while (!candidates.isEmpty() && candidates.peek().due <= now && processed++ < 64 && System.nanoTime() < deadline) {
            var candidate = candidates.remove();
            if (needed.test(candidate.key)) { queued.remove(candidate.key); continue; }
            int retryDelay = unload.applyAsInt(candidate.key);
            if (retryDelay < 0) queued.remove(candidate.key);
            else candidates.add(new Candidate(candidate.key, now + retryDelay));
        }
    }
    public void clear() { scan = new long[0]; cursor = 0; candidates.clear(); queued.clear(); }
    public int pending() { return queued.size(); }
}
