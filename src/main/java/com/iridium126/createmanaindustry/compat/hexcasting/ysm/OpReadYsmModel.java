package com.iridium126.createmanaindustry.compat.hexcasting.ysm;

import java.util.List;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;
import at.petrak.hexcasting.api.casting.castables.ConstMediaAction;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.iota.*;
import at.petrak.hexcasting.common.lib.hex.HexIotaTypes;
import at.petrak.hexcasting.api.casting.mishaps.*;
import com.iridium126.createmanaindustry.compat.ysm.YsmServerRuntime;
import com.iridium126.createmanaindustry.compat.ysm.YsmSnapshotStore;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Group;
import net.minecraft.server.level.ServerPlayer;

/** Read-only great spell. Resource preparation is asynchronous and can require a retry. */
public final class OpReadYsmModel implements ConstMediaAction {
    @Override public int getArgc() { return 1; }
    @Override public long getMediaCost() { return 0; }
    @Override public List<Iota> execute(List<? extends Iota> args, CastingEnvironment env) throws Mishap {
        if (!(args.getFirst() instanceof EntityIota entity) || !(entity.getEntity(env.getWorld()) instanceof ServerPlayer player))
            throw MishapInvalidIota.ofType(args.getFirst(), 0, "entity.player");
        if (player.serverLevel() != env.getWorld()) throw new MishapYsm("Target player is in another dimension");
        env.assertVecInRange(player.position());
        try {
            var result = YsmServerRuntime.get(env.getWorld().getServer()).read(player);
            if (result.status() != YsmSnapshotStore.Status.READY) throw new MishapYsm(result.reason());
            var roots = result.snapshot().roots();
            if (exceedsHexcastingLimits(roots))
                throw new MishapYsm("Model geometry exceeds Hexcasting's iota serialization limit");
            List<Iota> groups = roots.stream().<Iota>map(GroupIota::new).toList();
            ListIota model = new ListIota(groups);
            if (IotaType.isTooLargeToSerialize(List.of(model)))
                throw new MishapYsm("Model geometry exceeds Hexcasting's iota serialization limit");
            return List.of(model);
        } catch (IllegalStateException | IllegalArgumentException failure) { throw new MishapYsm(failure.getMessage()); }
    }

    /** Preflight iteratively so oversized/deep models are rejected before building a nested iota tree. */
    private static boolean exceedsHexcastingLimits(List<Group> roots) {
        record Measure(long size, long depth) {}
        Map<Group, Measure> measures = new IdentityHashMap<>();
        var traversal = new ArrayDeque<Group>();
        var postorder = new ArrayDeque<Group>();
        for (Group root : roots) traversal.push(root);
        while (!traversal.isEmpty()) {
            Group group = traversal.pop();
            postorder.push(group);
            for (Group child : group.children()) traversal.push(child);
        }
        while (!postorder.isEmpty()) {
            Group group = postorder.pop();
            long metadata = (long) group.name().length() + group.extraJson().length();
            if (group.root() != null)
                metadata += (long) group.root().descriptionJson().length() + group.root().part().length() + 64;
            long size = 12L + (metadata + 7) / 8;
            long depth = group.cubes().isEmpty() ? 1 : 2; // CubeIota has depth 1.
            for (var cube : group.cubes()) size = addCapped(size, 24L + (cube.extraJson().length() + 7L) / 8L);
            for (Group child : group.children()) {
                Measure childMeasure = measures.get(child);
                size = addCapped(size, childMeasure.size());
                depth = Math.max(depth, childMeasure.depth() + 1);
            }
            measures.put(group, new Measure(size, depth));
        }
        long total = 2; // the model ListIota and IotaType's starting count
        long maxDepth = 0;
        for (Group root : roots) {
            Measure measure = measures.get(root);
            total = addCapped(total, measure.size());
            maxDepth = Math.max(maxDepth, measure.depth());
        }
        return total >= HexIotaTypes.MAX_SERIALIZATION_TOTAL
                || maxDepth + 1 >= HexIotaTypes.MAX_SERIALIZATION_DEPTH; // outer model ListIota
    }

    private static long addCapped(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }
}
