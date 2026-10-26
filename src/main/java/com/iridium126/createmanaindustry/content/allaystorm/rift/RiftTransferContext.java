package com.iridium126.createmanaindustry.content.allaystorm.rift;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** One-use approvals, scoped to this synchronous transaction and its exact target. */
public final class RiftTransferContext implements AutoCloseable {
    private static final ThreadLocal<RiftTransferContext> CURRENT = new ThreadLocal<>();
    private final ResourceKey<Level> target;
    private final Set<UUID> approvals;
    private final Set<UUID> participants;
    private RiftTransferContext(ResourceKey<Level> target, Set<UUID> approvals) {
        if (CURRENT.get() != null) throw new IllegalStateException("Recursive rift transfer");
        this.target = target; this.approvals = new HashSet<>(approvals);this.participants=Set.copyOf(approvals); CURRENT.set(this);
    }
    public static RiftTransferContext open(ResourceKey<Level> target, Set<UUID> approvals) { return new RiftTransferContext(target, approvals); }
    public static boolean consume(Entity entity, ResourceKey<Level> target) {
        var context = CURRENT.get();
        return context != null && context.target.equals(target) && context.approvals.remove(entity.getUUID());
    }
    public static boolean contains(UUID entity){var context=CURRENT.get();return context!=null&&context.participants.contains(entity);}
    @Override public void close() { CURRENT.remove(); }
}
