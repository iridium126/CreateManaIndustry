package com.iridium126.createmanaindustry.compat.hexcasting.ysm;

import java.util.List;
import at.petrak.hexcasting.api.casting.castables.ConstMediaAction;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.iota.*;
import at.petrak.hexcasting.api.casting.mishaps.*;
import com.iridium126.createmanaindustry.compat.ysm.YsmServerRuntime;
import com.iridium126.createmanaindustry.compat.ysm.YsmSnapshotStore;
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
            List<Iota> groups = result.snapshot().roots().stream().<Iota>map(GroupIota::new).toList();
            ListIota model = new ListIota(groups);
            if (IotaType.isTooLargeToSerialize(List.of(model)))
                throw new MishapYsm("Model geometry exceeds Hexcasting's iota serialization limit");
            return List.of(model);
        } catch (IllegalStateException | IllegalArgumentException failure) { throw new MishapYsm(failure.getMessage()); }
    }
}
