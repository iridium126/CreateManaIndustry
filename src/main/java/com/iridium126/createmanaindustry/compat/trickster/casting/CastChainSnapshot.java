package com.iridium126.createmanaindustry.compat.trickster.casting;

import com.iridium126.createmanaindustry.compat.hextrick.execution.CastingOrigin;
import com.iridium126.createmanaindustry.compat.hextrick.execution.CircleSourceDescriptor;
import com.iridium126.createmanaindustry.compat.trickster.partition.PartitionBounds;
import com.iridium126.createmanaindustry.compat.trickster.partition.SavedPartition;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.*;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/** Immutable effect metadata; no live server, entity, executor or ownership references. */
public record CastChainSnapshot(UUID id, Optional<UUID> player, boolean inverted, List<FateTarget> targets,
        Optional<SavedPartition> partition, Optional<CastingOrigin> partitionOrigin) {
    public CastChainSnapshot {
        Objects.requireNonNull(id);
        Objects.requireNonNull(player);
        targets = List.copyOf(targets);
        Objects.requireNonNull(partition);
        Objects.requireNonNull(partitionOrigin);
        var ids = new HashSet<UUID>();
        for (var target : targets) if (!ids.add(target.id())) throw new IllegalArgumentException("Duplicate fate target");
    }
    public record FateTarget(UUID id, Optional<CastingOrigin> origin) {
        public FateTarget { Objects.requireNonNull(id); Objects.requireNonNull(origin); }
    }

    public static final Codec<PartitionBounds> BOUNDS_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("min_x").forGetter(PartitionBounds::minX),
            Codec.INT.fieldOf("min_y").forGetter(PartitionBounds::minY),
            Codec.INT.fieldOf("min_z").forGetter(PartitionBounds::minZ),
            Codec.INT.fieldOf("max_x").forGetter(PartitionBounds::maxX),
            Codec.INT.fieldOf("max_y").forGetter(PartitionBounds::maxY),
            Codec.INT.fieldOf("max_z").forGetter(PartitionBounds::maxZ)).apply(i, PartitionBounds::new));
    public static final Codec<SavedPartition> PARTITION_CODEC = RecordCodecBuilder.create(i -> i.group(
            ResourceLocation.CODEC.fieldOf("dimension").forGetter(SavedPartition::dimension),
            BOUNDS_CODEC.fieldOf("bounds").forGetter(SavedPartition::bounds)).apply(i, SavedPartition::new));
    public static final Codec<CircleSourceDescriptor.BlockReference> BLOCK_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("x").forGetter(CircleSourceDescriptor.BlockReference::x),
            Codec.INT.fieldOf("y").forGetter(CircleSourceDescriptor.BlockReference::y),
            Codec.INT.fieldOf("z").forGetter(CircleSourceDescriptor.BlockReference::z),
            ResourceLocation.CODEC.fieldOf("type").forGetter(CircleSourceDescriptor.BlockReference::type),
            UUIDUtil.CODEC.fieldOf("identity").forGetter(CircleSourceDescriptor.BlockReference::identity))
            .apply(i, CircleSourceDescriptor.BlockReference::new));
    public static final Codec<CircleSourceDescriptor> SOURCE_CODEC = RecordCodecBuilder.create(i -> i.group(
            ResourceLocation.CODEC.fieldOf("dimension").forGetter(CircleSourceDescriptor::dimension),
            BLOCK_CODEC.fieldOf("impetus").forGetter(CircleSourceDescriptor::impetus),
            BLOCK_CODEC.fieldOf("anchor").forGetter(CircleSourceDescriptor::anchor),
            BLOCK_CODEC.listOf(0, 64000).fieldOf("slots").forGetter(CircleSourceDescriptor::slots))
            .apply(i, CircleSourceDescriptor::new));
    public static final Codec<CastingOrigin> ORIGIN_CODEC = RecordCodecBuilder.create(i -> i.group(
            SOURCE_CODEC.fieldOf("source").forGetter(CastingOrigin::source),
            Direction.CODEC.fieldOf("facing").forGetter(CastingOrigin::facing),
            BOUNDS_CODEC.fieldOf("bounds").forGetter(o -> new PartitionBounds(o.minX(), o.minY(), o.minZ(), o.maxX(), o.maxY(), o.maxZ())),
            UUIDUtil.CODEC.optionalFieldOf("player").forGetter(o -> Optional.ofNullable(o.player())))
            .apply(i, (source, facing, bounds, player) -> new CastingOrigin(source, facing,
                    bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ(), player.orElse(null))));
    public static final Codec<FateTarget> TARGET_CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(FateTarget::id),
            ORIGIN_CODEC.optionalFieldOf("origin").forGetter(FateTarget::origin)).apply(i, FateTarget::new));
    public static final Codec<CastChainSnapshot> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(CastChainSnapshot::id),
            UUIDUtil.CODEC.optionalFieldOf("player").forGetter(CastChainSnapshot::player),
            Codec.BOOL.fieldOf("inverted").forGetter(CastChainSnapshot::inverted),
            TARGET_CODEC.listOf().fieldOf("targets").forGetter(CastChainSnapshot::targets),
            PARTITION_CODEC.optionalFieldOf("partition").forGetter(CastChainSnapshot::partition),
            ORIGIN_CODEC.optionalFieldOf("partition_origin").forGetter(CastChainSnapshot::partitionOrigin))
            .apply(i, CastChainSnapshot::new));
    public static final StreamCodec<ByteBuf, CastChainSnapshot> STREAM_CODEC = ByteBufCodecs.fromCodec(CODEC);
}
