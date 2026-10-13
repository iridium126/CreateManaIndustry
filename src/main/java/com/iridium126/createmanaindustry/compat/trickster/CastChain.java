package com.iridium126.createmanaindustry.compat.trickster;

import java.util.*;

/** Shared by synchronous calls and retained continuation owners, never by a whole player. */
public final class CastChain {
    private final UUID id;
    private UUID player;
    private boolean inverted;
    private final Set<Object> owners = Collections.newSetFromMap(new IdentityHashMap<>());
    public CastChain() { this(UUID.randomUUID(), null, false); }
    private CastChain(UUID id, UUID player, boolean inverted) { this.id=id; this.player=player; this.inverted=inverted; }
    public UUID id() { return id; }
    public UUID player() { return player; }
    public boolean inverted() { return inverted; }
    public boolean live() { return !owners.isEmpty(); }
    public void bind(UUID player) {
        if (this.player != null && !this.player.equals(player)) throw new IllegalStateException("Cross-player casting chain");
        this.player = player;
    }
    public void retain(Object owner) { owners.add(owner); }
    public void release(Object owner) { owners.remove(owner); }
    public void suspend() { owners.clear(); }
    public void toggle() { inverted = !inverted; }
    public String encode() { return id + "/" + (player == null ? "" : player) + "/" + inverted; }
    public static CastChain decode(String value) {
        if (value == null || value.isEmpty()) return new CastChain();
        String[] fields = value.split("/", -1);
        if (fields.length != 3 || !(fields[2].equals("true") || fields[2].equals("false")))
            throw new IllegalArgumentException("Invalid casting chain");
        return new CastChain(UUID.fromString(fields[0]), fields[1].isEmpty() ? null : UUID.fromString(fields[1]), Boolean.parseBoolean(fields[2]));
    }
}
