package com.iridium126.createmanaindustry.compat.hextrick.execution;

import com.iridium126.createmanaindustry.compat.trickster.casting.CastChains;
import com.iridium126.createmanaindustry.compat.trickster.partition.PartitionFees;
import com.iridium126.createmanaindustry.compat.trickster.partition.PartitionManager;
import dev.enjarai.trickster.spell.execution.source.SpellSource;
import org.joml.Vector3dc;

/** Native 16-block item/message reach, including partition and inversion policy. */
public final class CastingRanges {
    private CastingRanges() {}

    public static double reach(SpellSource source, Vector3dc from, Vector3dc to, double distance) {
        if (PartitionManager.crosses(source.getWorld(), PartitionFees.vec(from), PartitionFees.vec(to))) {
            distance = Double.POSITIVE_INFINITY;
        }
        return source.getPlayer().map(player -> CastChains.invertedFor(player.getUUID())).orElse(false)
                ? (distance <= 16 ? Math.nextUp(16D) : 16D) : distance;
    }
}
