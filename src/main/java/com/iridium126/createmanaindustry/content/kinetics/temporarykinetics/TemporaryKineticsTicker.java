package com.iridium126.createmanaindustry.content.kinetics.temporarykinetics;

import com.iridium126.createmanaindustry.CMIAttachments;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import it.unimi.dsi.fastutil.longs.Long2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

@EventBusSubscriber(modid = CreateManaIndustry.MODID)
public final class TemporaryKineticsTicker {
    private TemporaryKineticsTicker() {}

    public static void queue(ServerLevel level, KineticNetwork network) {
        level.getData(CMIAttachments.TEMPORARY_KINETICS_UPDATES).pending.add(network);
    }

    static void scheduleExpiry(ServerLevel level, KineticBlockEntity be, long previous, long next) {
        if (previous == next)
            return;

        LevelUpdates updates = next == 0 ? level.getExistingDataOrNull(CMIAttachments.TEMPORARY_KINETICS_UPDATES)
                : level.getData(CMIAttachments.TEMPORARY_KINETICS_UPDATES);
        if (updates == null)
            return;
        var deadlines = updates.deadlines;
        if (previous != 0) {
            var scheduled = deadlines.get(previous);
            if (scheduled != null && scheduled.remove(be) && scheduled.isEmpty())
                deadlines.remove(previous);
        }
        if (next != 0)
            deadlines.computeIfAbsent(next, deadline -> new ReferenceOpenHashSet<>()).add(be);
    }

    @SubscribeEvent
    public static void tick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            LevelUpdates updates = level.getExistingDataOrNull(CMIAttachments.TEMPORARY_KINETICS_UPDATES);
            if (updates != null) {
                updates.expireThrough(level.getGameTime());
                updates.flush();
            }
        }
    }

    public static final class LevelUpdates {
        // A loaded chunk need not be ticking its block entities.
        private final Long2ObjectAVLTreeMap<ReferenceOpenHashSet<KineticBlockEntity>> deadlines =
                new Long2ObjectAVLTreeMap<>();
        private ReferenceOpenHashSet<KineticNetwork> pending = new ReferenceOpenHashSet<>();
        private ReferenceOpenHashSet<KineticNetwork> publishing = new ReferenceOpenHashSet<>();

        private void expireThrough(long time) {
            while (!deadlines.isEmpty()) {
                long deadline = deadlines.firstLongKey();
                if (deadline > time)
                    return;
                for (KineticBlockEntity be : deadlines.remove(deadline)) {
                    if (!be.isRemoved())
                        TemporaryKinetics.beforeTick(be);
                }
            }
        }

        private void flush() {
            if (pending.isEmpty())
                return;

            var ready = pending;
            pending = publishing;
            publishing = ready;
            try {
                for (KineticNetwork network : ready)
                    TemporaryKineticsNetwork.of(network).createmanaindustry$publishTemporaryCapacity();
            } finally {
                ready.clear();
            }
        }
    }
}
