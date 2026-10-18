package com.iridium126.createmanaindustry.compat.hextrick.execution;

import com.iridium126.createmanaindustry.compat.hextrick.execution.circle.SlateKnotInventory;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Identifiers only. Resolving never adds chunk tickets or stores fuel snapshots. */
public record CircleSourceDescriptor(ResourceLocation dimension, BlockReference impetus,
        BlockReference anchor, List<BlockReference> slots) {
    public enum Status { READY, WAITING, INVALID }
    public record Resolution(Status status, ServerLevel level, String reason) {}

    public CircleSourceDescriptor {
        Objects.requireNonNull(dimension, "dimension");
        if (!ResourceLocation.parse(dimension.toString()).equals(dimension))
            throw new IllegalArgumentException("Invalid source dimension");
        Objects.requireNonNull(impetus, "impetus");
        Objects.requireNonNull(anchor, "anchor");
        slots = List.copyOf(slots);
        if (slots.size() > 64000) throw new IllegalArgumentException("Too many circle sources");
    }

    public record BlockReference(int x, int y, int z, ResourceLocation type, UUID identity) {
        public BlockReference {
            Objects.requireNonNull(type, "block entity type");
            if (!ResourceLocation.parse(type.toString()).equals(type))
                throw new IllegalArgumentException("Invalid block entity type");
            Objects.requireNonNull(identity, "block identity");
        }
        public BlockPos pos() { return new BlockPos(x, y, z); }

        public static BlockReference capture(BlockEntity block) {
            if (!(block instanceof SourceIdentity source)) throw new IllegalArgumentException("Not a circle source");
            BlockPos pos = block.getBlockPos();
            return new BlockReference(pos.getX(), pos.getY(), pos.getZ(),
                    BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(block.getType()), source.cmi$sourceIdentity());
        }

        @SuppressWarnings("deprecation")
        Status inspect(ServerLevel world) {
            if (!world.hasChunkAt(pos())) return Status.WAITING;
            BlockEntity block = world.getBlockEntity(pos());
            return block != null && !block.isRemoved() && block instanceof SourceIdentity source
                    && identity.equals(source.cmi$sourceIdentity())
                    && type.equals(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(block.getType()))
                    ? Status.READY : Status.INVALID;
        }
    }

    public Resolution resolve(MinecraftServer server) {
        ServerLevel world = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
        if (world == null) return new Resolution(Status.WAITING, null, "source_dimension_unavailable");
        boolean waiting = false;
        var references = new ArrayList<>(slots);
        references.add(impetus);
        references.add(anchor);
        for (BlockReference reference : references) {
            Status status = reference.inspect(world);
            if (status == Status.INVALID) return new Resolution(status, world, "source_replaced");
            waiting |= status == Status.WAITING;
        }
        return new Resolution(waiting ? Status.WAITING : Status.READY, world,
                waiting ? "source_chunk_unloaded" : "ready");
    }

    public SlateKnotInventory inventory(ServerLevel world) {
        return SlateKnotInventory.forOrderedPositions(world, slots.stream().map(BlockReference::pos).toList());
    }
}
