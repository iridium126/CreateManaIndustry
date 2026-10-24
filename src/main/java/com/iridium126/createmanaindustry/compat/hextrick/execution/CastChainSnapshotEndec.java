package com.iridium126.createmanaindustry.compat.hextrick.execution;

import com.iridium126.createmanaindustry.compat.trickster.casting.CastChainSnapshot;
import com.iridium126.createmanaindustry.compat.trickster.partition.*;
import io.wispforest.endec.Endec;
import io.wispforest.endec.StructEndec;
import io.wispforest.endec.impl.StructEndecBuilder;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

/** Loaded only by the Trickster bridge; the common snapshot has no Endec dependency. */
public final class CastChainSnapshotEndec {
    private CastChainSnapshotEndec() {}
    private static final Endec<UUID> UUID_ENDEC = StructEndecBuilder.of(
            Endec.LONG.fieldOf("most", UUID::getMostSignificantBits),
            Endec.LONG.fieldOf("least", UUID::getLeastSignificantBits), UUID::new);
    private static final Endec<ResourceLocation> LOCATION = Endec.STRING.xmap(ResourceLocation::parse, ResourceLocation::toString);
    private static final StructEndec<PartitionBounds> BOUNDS = StructEndecBuilder.of(
            Endec.INT.fieldOf("min_x", PartitionBounds::minX), Endec.INT.fieldOf("min_y", PartitionBounds::minY),
            Endec.INT.fieldOf("min_z", PartitionBounds::minZ), Endec.INT.fieldOf("max_x", PartitionBounds::maxX),
            Endec.INT.fieldOf("max_y", PartitionBounds::maxY), Endec.INT.fieldOf("max_z", PartitionBounds::maxZ), PartitionBounds::new);
    private static final StructEndec<SavedPartition> PARTITION = StructEndecBuilder.of(
            LOCATION.fieldOf("dimension", SavedPartition::dimension), BOUNDS.fieldOf("bounds", SavedPartition::bounds), SavedPartition::new);
    private static final StructEndec<CircleSourceDescriptor.BlockReference> BLOCK = StructEndecBuilder.of(
            Endec.INT.fieldOf("x", CircleSourceDescriptor.BlockReference::x),
            Endec.INT.fieldOf("y", CircleSourceDescriptor.BlockReference::y),
            Endec.INT.fieldOf("z", CircleSourceDescriptor.BlockReference::z),
            LOCATION.fieldOf("type", CircleSourceDescriptor.BlockReference::type),
            UUID_ENDEC.fieldOf("identity", CircleSourceDescriptor.BlockReference::identity), CircleSourceDescriptor.BlockReference::new);
    private static final StructEndec<CircleSourceDescriptor> SOURCE = StructEndecBuilder.of(
            LOCATION.fieldOf("dimension", CircleSourceDescriptor::dimension), BLOCK.fieldOf("impetus", CircleSourceDescriptor::impetus),
            BLOCK.fieldOf("anchor", CircleSourceDescriptor::anchor), BLOCK.listOf().fieldOf("slots", CircleSourceDescriptor::slots), CircleSourceDescriptor::new);
    private static final StructEndec<CastingOrigin> ORIGIN = StructEndecBuilder.of(
            SOURCE.fieldOf("source", CastingOrigin::source), Endec.forEnum(Direction.class).fieldOf("facing", CastingOrigin::facing),
            BOUNDS.fieldOf("bounds", (CastingOrigin o) -> new PartitionBounds(o.minX(), o.minY(), o.minZ(), o.maxX(), o.maxY(), o.maxZ())),
            UUID_ENDEC.optionalOf().fieldOf("player", (CastingOrigin o) -> Optional.ofNullable(o.player())),
            (source, facing, b, player) -> new CastingOrigin(source, facing, b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ(), player.orElse(null)));
    private static final StructEndec<CastChainSnapshot.FateTarget> TARGET = StructEndecBuilder.of(
            UUID_ENDEC.fieldOf("id", CastChainSnapshot.FateTarget::id),
            ORIGIN.optionalOf().fieldOf("origin", CastChainSnapshot.FateTarget::origin), CastChainSnapshot.FateTarget::new);
    public static final StructEndec<CastChainSnapshot> ENDEC = StructEndecBuilder.of(
            UUID_ENDEC.fieldOf("id", CastChainSnapshot::id), UUID_ENDEC.optionalOf().fieldOf("player", CastChainSnapshot::player),
            Endec.BOOLEAN.fieldOf("inverted", CastChainSnapshot::inverted), TARGET.listOf().fieldOf("targets", CastChainSnapshot::targets),
            PARTITION.optionalOf().fieldOf("partition", CastChainSnapshot::partition),
            ORIGIN.optionalOf().fieldOf("partition_origin", CastChainSnapshot::partitionOrigin), CastChainSnapshot::new);
}
