package com.iridium126.createmanaindustry.dimension.cube;

import java.util.*;
import java.util.function.LongPredicate;

/** Only failed requests occupy this queue; successful steady watches perform no retry scans. */
public final class AllvrRetryQueue {
    private record Attempt(long key, int failures, long due) {}
    private final Map<Long, Attempt> attempts = new HashMap<>();
    private final PriorityQueue<Attempt> due = new PriorityQueue<>(Comparator.comparingLong(Attempt::due));
    public void failed(long key, long now) {
        var previous = attempts.get(key);
        int failures = previous == null ? 1 : Math.min(5, previous.failures + 1);
        var attempt = new Attempt(key, failures, now + Math.min(200, 20L << (failures - 1)));
        attempts.put(key, attempt); due.add(attempt);
    }
    public List<Long> poll(long now, int budget) {
        var result = new ArrayList<Long>();
        while (budget-- > 0 && !due.isEmpty() && due.peek().due <= now) {
            var entry = due.remove();
            if (attempts.get(entry.key) == entry) result.add(entry.key);
        }
        return result;
    }
    public void succeeded(long key) { attempts.remove(key); }
    public void retain(LongPredicate needed) {
        attempts.keySet().removeIf(key -> !needed.test(key));
        due.removeIf(entry -> attempts.get(entry.key) != entry);
    }
    public void clear() { attempts.clear(); due.clear(); }
    public int size() { return attempts.size(); }
}
