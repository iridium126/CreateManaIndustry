package com.iridium126.createmanaindustry.content.allaystorm.rift;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Incremental cold preparation; neither requesting a chunk future nor reading an absent chunk is allowed here. */
public final class RiftLandingSearch implements AutoCloseable {
    private static final TicketType<UUID> TICKET = TicketType.create("cmi_rift", UUID::compareTo);
    private final ServerLevel target;
    private final UUID transaction;
    private final StormTransferGroup group;
    private final double anchorX, anchorZ;
    private final List<ChunkPos> required = new ArrayList<>();
    private final Set<ChunkPos> requested = new LinkedHashSet<>();
    private int offset, y = 383;
    private Vec3 result;
    private boolean exhausted;
    public RiftLandingSearch(ServerLevel target, UUID transaction, StormTransferGroup group, double x, double z) {
        this.target = target; this.transaction = transaction; this.group = group; anchorX = x; anchorZ = z;
        AABB union = group.geometry().stream().reduce(AABB::minmax).orElseThrow();
        if (union.getYsize() > 512) { exhausted = true; return; }
        AABB footprint = union.move(x - group.reference().x, 0, z - group.reference().z).inflate(33, 0, 33);
        int minX = Mth.floor(footprint.minX) >> 4, maxX = Mth.floor(footprint.maxX) >> 4;
        int minZ = Mth.floor(footprint.minZ) >> 4, maxZ = Mth.floor(footprint.maxZ) >> 4;
        // At two new chunk requests per tick, a larger cold footprint cannot finish in sixty seconds.
        if(((long)maxX-minX+1)*((long)maxZ-minZ+1)>2400) { exhausted=true;return; }
        for (int cx = minX; cx <= maxX; cx++) for (int cz = minZ; cz <= maxZ; cz++) required.add(new ChunkPos(cx, cz));
        required.sort(Comparator.comparingDouble(p -> Math.pow(p.getMiddleBlockX()-x, 2)+Math.pow(p.getMiddleBlockZ()-z, 2)));
    }
    /** Caller shares the two-request and one-millisecond budget across all in-flight groups. */
    public int prepare(int newRequests) {
        int used = 0;
        for (ChunkPos pos : required) {
            if (requested.contains(pos)) continue;
            if (used >= newRequests) break;
            target.getChunkSource().addRegionTicket(TICKET, pos, 0, transaction);
            requested.add(pos); used++;
        }
        return used;
    }
    public boolean ready() {
        if (requested.size() != required.size()) return false;
        for (ChunkPos pos : required) if (target.getChunkSource().getChunkNow(pos.x, pos.z) == null || !target.areEntitiesLoaded(pos.toLong())) return false;
        return true;
    }
    public void step(long deadline, Collection<AABB> reservations) {
        if (result != null || exhausted || !ready()) return;
        Entity root = group.members().stream().filter(e -> e.getVehicle() == null).findFirst().orElseThrow();
        while (System.nanoTime() < deadline && offset < RiftLandingOffsets.OFFSETS.size()) {
            var shift = RiftLandingOffsets.OFFSETS.get(offset);
            double x = anchorX + shift.x(), z = anchorZ + shift.z();
            BlockPos support = BlockPos.containing(x, y, z);
            var block = target.getBlockState(support);
            var shape = block.getCollisionShape(target, support);
            boolean water = root instanceof Boat && block.getFluidState().is(FluidTags.WATER);
            if (!root.getType().isBlockDangerous(block) && (!shape.isEmpty() || water)) {
                double surface = y + (water ? block.getFluidState().getHeight(target, support) : shape.max(Direction.Axis.Y));
                Vec3 destination = new Vec3(x, surface + (group.reference().y-root.getBoundingBox().minY), z);
                if (safe(group, destination, reservations)) { result = destination; return; }
            }
            if (--y < -128) { offset++; y = 383; }
        }
        exhausted = offset >= RiftLandingOffsets.OFFSETS.size();
    }
    public boolean safe(StormTransferGroup current, Vec3 destination, Collection<AABB> reservations) {
        Vec3 delta = destination.subtract(current.reference());
        var boxes = current.geometry();
        for (int i = 0; i < boxes.size(); i++) {
            AABB box = boxes.get(i).move(delta).deflate(1e-6);
            if (!RiftRules.nativeBand(box.minY, box.maxY) || box.minY<target.getMinBuildHeight() || box.maxY>target.getMaxBuildHeight() || !target.getWorldBorder().isWithinBounds(box)) return false;
            int minX = Mth.floor(box.minX-1)>>4, maxX = Mth.floor(box.maxX+1)>>4;
            int minZ = Mth.floor(box.minZ-1)>>4, maxZ = Mth.floor(box.maxZ+1)>>4;
            for (int cx = minX; cx <= maxX; cx++) for (int cz = minZ; cz <= maxZ; cz++)
                if (target.getChunkSource().getChunkNow(cx, cz) == null || !target.areEntitiesLoaded(ChunkPos.asLong(cx, cz))) return false;
            Entity entity = current.members().get(Math.min(i, current.members().size()-1));
            if (!target.noCollision(entity, box)) return false;
            if (reservations.stream().anyMatch(box::intersects)) return false;
            for (BlockPos pos : BlockPos.betweenClosed(Mth.floor(box.minX), Mth.floor(box.minY), Mth.floor(box.minZ), Mth.floor(box.maxX), Mth.floor(box.maxY), Mth.floor(box.maxZ)))
                if (entity.getType().isBlockDangerous(target.getBlockState(pos))) return false;
        }
        return true;
    }
    public Vec3 result() { return result; }
    public boolean exhausted() { return exhausted; }
    @Override public void close() {
        for (ChunkPos pos : requested) target.getChunkSource().removeRegionTicket(TICKET, pos, 0, transaction);
        requested.clear();
    }
}
