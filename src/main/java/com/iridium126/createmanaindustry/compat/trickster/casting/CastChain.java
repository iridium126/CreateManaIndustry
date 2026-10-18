package com.iridium126.createmanaindustry.compat.trickster.casting;

import com.iridium126.createmanaindustry.compat.hextrick.execution.CastingOrigin;
import com.iridium126.createmanaindustry.compat.trickster.partition.SavedPartition;
import java.util.*;
import net.minecraft.server.MinecraftServer;

/** Shared by synchronous calls and retained continuation owners, never by a whole player. */
public final class CastChain {
    private final UUID id;
    private UUID player;
    private MinecraftServer runtime;
    private boolean inverted;
    private final Set<UUID> fateTargets = new LinkedHashSet<>();
    private final Map<UUID, CastingOrigin> fateOrigins = new HashMap<>();
    private CastingOrigin partitionOrigin;
    private final Set<Object> owners = Collections.newSetFromMap(new IdentityHashMap<>());
    public CastChain() {
        this(UUID.randomUUID(), null, false);
    }
    private CastChain(UUID id, UUID player, boolean inverted) {
        this.id=id;
        this.player=player;
        this.inverted=inverted;
    }
    private SavedPartition partition;
    public SavedPartition partition() {
        return partition;
    }
    public void partition(SavedPartition value) {
        partition=value;
    }
    public UUID id() {
        return id;
    }
    public UUID player() {
        return player;
    }
    public MinecraftServer runtime() {
        return runtime;
    }
    public void runtime(MinecraftServer server) {
        if (runtime != null && runtime != server) throw new IllegalStateException("Cross-server cast chain");
        runtime = server;
    }
    public boolean inverted() {
        return inverted;
    }
    public List<UUID> fateTargets() {
        return List.copyOf(fateTargets);
    }
    public void addFateTarget(UUID target) {
        fateTargets.add(target);
    }
    public void addFateTarget(UUID target, CastingOrigin origin) {
        fateTargets.add(target);
        if (origin != null) fateOrigins.put(target, origin);
    }
    public CastingOrigin fateOrigin(UUID target) {
        return fateOrigins.get(target);
    }
    public CastingOrigin partitionOrigin() {
        return partitionOrigin;
    }
    public void partitionOrigin(CastingOrigin origin) {
        partitionOrigin = origin;
    }
    public void removeFateTarget(UUID target) {
        fateTargets.remove(target);
        fateOrigins.remove(target);
    }
    public void restoreFateTargets(List<String> targets) {
        fateTargets.clear();
        targets.forEach(value -> fateTargets.add(UUID.fromString(value)));
        fateOrigins.keySet().retainAll(fateTargets);
    }
    public String encodeOrigins() {
        var encoded = new com.google.gson.JsonObject();
        fateOrigins.forEach((target, origin) -> encoded.addProperty(target.toString(), origin.encode()));
        if (partitionOrigin != null) encoded.addProperty("partition", partitionOrigin.encode());
        return encoded.toString();
    }
    public void restoreOrigins(String encoded) {
        if (encoded.length() > 2 * 1024 * 1024) throw new IllegalArgumentException("Effect origins too large");
        var data = com.google.gson.JsonParser.parseString(encoded).getAsJsonObject();
        fateOrigins.clear();
        partitionOrigin = null;
        data.entrySet().forEach(entry -> {
            var origin = CastingOrigin.decode(entry.getValue().getAsString());
            if (entry.getKey().equals("partition")) partitionOrigin = origin;
            else {
                UUID target = UUID.fromString(entry.getKey());
                if (fateTargets.contains(target)) fateOrigins.put(target, origin);
            }
        });
    }
    public boolean live() {
        return !owners.isEmpty();
    }
    public void bind(UUID player) {
        if (this.player != null && !this.player.equals(player)) throw new IllegalStateException("Cross-player casting chain");
        this.player = player;
    }
    public void retain(Object owner) {
        owners.add(owner);
    }
    public void release(Object owner) {
        owners.remove(owner);
    }
    public void suspend() {
        owners.clear();
    }
    public void toggle() {
        inverted = !inverted;
    }
    public String encode() {
        return id + "/" + (player == null ? "" : player) + "/" + inverted;
    }
    public static CastChain decode(String value) {
        if (value == null || value.isEmpty()) return new CastChain();
        String[] fields = value.split("/", -1);
        if (fields.length != 3 || !(fields[2].equals("true") || fields[2].equals("false")))
            throw new IllegalArgumentException("Invalid casting chain");
        return new CastChain(UUID.fromString(fields[0]), fields[1].isEmpty() ? null : UUID.fromString(fields[1]), Boolean.parseBoolean(fields[2]));
    }
}
