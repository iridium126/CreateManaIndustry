package com.iridium126.createmanaindustry.compat.trickster.casting;

import com.iridium126.createmanaindustry.compat.hextrick.execution.CastingOrigin;
import com.iridium126.createmanaindustry.compat.trickster.partition.SavedPartition;
import java.util.*;
import net.minecraft.server.MinecraftServer;

/** Server-thread runtime authority, shared by calls and retained continuation owners. */
public final class CastChain {
    private final UUID id;
    private UUID player;
    private MinecraftServer runtime;
    private boolean inverted;
    private final Set<UUID> fateTargets = new LinkedHashSet<>();
    private final Map<UUID, CastingOrigin> fateOrigins = new HashMap<>();
    private CastingOrigin partitionOrigin;
    private SavedPartition partition;
    private final Set<Object> owners = Collections.newSetFromMap(new IdentityHashMap<>());
    private long stateVersion;
    private CastChainSnapshot cachedSnapshot;
    private List<UUID> cachedTargets = List.of();
    private boolean targetsDirty;

    public CastChain() { this(UUID.randomUUID()); }
    private CastChain(UUID id) { this.id = id; }
    private void changed() { stateVersion++; cachedSnapshot = null; }
    public long stateVersion() { return stateVersion; }
    public UUID id() { return id; }
    public UUID player() { return player; }
    public MinecraftServer runtime() { return runtime; }
    public boolean inverted() { return inverted; }
    public SavedPartition partition() { return partition; }
    public CastingOrigin partitionOrigin() { return partitionOrigin; }
    public CastingOrigin fateOrigin(UUID target) { return fateOrigins.get(target); }

    public void partition(SavedPartition value) {
        if (!Objects.equals(partition, value)) { partition = value; changed(); }
    }
    public void partitionOrigin(CastingOrigin value) {
        if (!Objects.equals(partitionOrigin, value)) { partitionOrigin = value; changed(); }
    }
    public void runtime(MinecraftServer server) {
        if (runtime != null && runtime != server) throw new IllegalStateException("Cross-server cast chain");
        runtime = Objects.requireNonNull(server);
    }
    public List<UUID> fateTargets() {
        if (targetsDirty) { cachedTargets = List.copyOf(fateTargets); targetsDirty = false; }
        return cachedTargets;
    }
    public void addFateTarget(UUID target) { addFateTarget(target, null); }
    public void addFateTarget(UUID target, CastingOrigin origin) {
        boolean added = fateTargets.add(Objects.requireNonNull(target));
        if (added) targetsDirty = true;
        boolean originChanged = origin != null && !Objects.equals(fateOrigins.put(target, origin), origin);
        if (added || originChanged) changed();
    }
    public void removeFateTarget(UUID target) {
        if (fateTargets.remove(target)) { fateOrigins.remove(target); targetsDirty = true; changed(); }
    }
    public void clearFateTargets() {
        if (!fateTargets.isEmpty()) { fateTargets.clear(); fateOrigins.clear(); targetsDirty = true; changed(); }
    }
    public void bind(UUID player) {
        Objects.requireNonNull(player);
        if (this.player != null && !this.player.equals(player)) throw new IllegalStateException("Cross-player casting chain");
        if (this.player == null) { this.player = player; changed(); }
    }
    public void toggle() { inverted = !inverted; changed(); }
    public boolean live() { return !owners.isEmpty(); }
    public void retain(Object owner) { owners.add(Objects.requireNonNull(owner)); }
    public void release(Object owner) { owners.remove(owner); }
    public void suspend() { owners.clear(); }

    public CastChainSnapshot snapshot() {
        if (cachedSnapshot == null) {
            var targets = new ArrayList<CastChainSnapshot.FateTarget>(fateTargets.size());
            for (UUID target : fateTargets) targets.add(new CastChainSnapshot.FateTarget(target, Optional.ofNullable(fateOrigins.get(target))));
            cachedSnapshot = new CastChainSnapshot(id, Optional.ofNullable(player), inverted, targets,
                    Optional.ofNullable(partition), Optional.ofNullable(partitionOrigin));
        }
        return cachedSnapshot;
    }
    public static CastChain restore(CastChainSnapshot snapshot) {
        var chain = new CastChain(snapshot.id());
        chain.player = snapshot.player().orElse(null);
        chain.inverted = snapshot.inverted();
        for (var target : snapshot.targets()) {
            chain.fateTargets.add(target.id());
            target.origin().ifPresent(origin -> chain.fateOrigins.put(target.id(), origin));
        }
        chain.targetsDirty = !chain.fateTargets.isEmpty();
        chain.partition = snapshot.partition().orElse(null);
        chain.partitionOrigin = snapshot.partitionOrigin().orElse(null);
        chain.cachedSnapshot = snapshot;
        return chain;
    }
}
