package com.iridium126.createmanaindustry.content.allaystorm;

import com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormRingPacket.Shot;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import static com.iridium126.createmanaindustry.content.allaystorm.StormRingGeometry.*;

/** Server-side progression of the same finite ray sent to the effect emitter. */
final class StormManaBeam {
    final Shot shot;
    private final Vec3 origin, axis;
    private final BlockHitResult block;
    private final HashSet<UUID> hitPlayers = new HashSet<>();
    private double head = -1e-6;
    private boolean impacted;

    StormManaBeam(ServerLevel level, Vec3 origin, Vec3 axis, long start) {
        this.origin = origin;
        this.axis = axis;
        Vec3 end = origin.add(axis.scale(BEAM_RANGE));
        var hit = level.clip(new ClipContext(origin, end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, CollisionContext.empty()));
        block = hit.getType() == HitResult.Type.BLOCK ? hit : null;
        float distance = block == null ? BEAM_RANGE
                : Math.clamp((float) hit.getLocation().subtract(origin).dot(axis), 0, BEAM_RANGE);
        shot = new Shot(start, distance, block == null ? null : hit.getDirection());
    }

    boolean advance(ServerLevel level, long now, List<BlockPos> removed) {
        double age = (now - shot.tick()) / 20.0;
        double next = Math.min(shot.distance(), age * BEAM_SPEED);
        if (next > head) {
            damagePlayers(level, origin.add(axis.scale(Math.max(0, head))), origin.add(axis.scale(next)), hitPlayers);
            head = next;
        }
        if (!impacted && head >= shot.distance()) {
            impacted = true;
            if (block != null) {
                var pos = block.getBlockPos();
                var shape = level.getBlockState(pos).getCollisionShape(level, pos);
                var hit = shape.clip(origin, block.getLocation().add(axis.scale(.001)), pos);
                if (hit != null) removed.addAll(StormBlast.explode(level, hit.getLocation()));
            }
        }
        return age < shot.distance() / BEAM_SPEED + HOLD + FADE;
    }

    static void damagePlayers(ServerLevel level, Vec3 from, Vec3 to, HashSet<UUID> hitPlayers) {
        if (ServerConfig.stormManaBeamDamage <= 0) return;
        for (ServerPlayer player : level.getEntitiesOfClass(ServerPlayer.class, new AABB(from, to).inflate(.001),
                p -> p.isAlive() && !p.isSpectator())) {
            var box = player.getBoundingBox();
            if ((box.contains(from) || box.clip(from, to).isPresent()) && hitPlayers.add(player.getUUID())) {
                player.hurt(level.damageSources().magic(), (float) ServerConfig.stormManaBeamDamage);
            }
        }
    }
}
