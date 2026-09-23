package com.iridium126.createmanaindustry.compat.hexcasting.ysm;

import java.util.List;
import java.util.UUID;
import at.petrak.hexcasting.api.casting.RenderedSpell;
import at.petrak.hexcasting.api.casting.castables.SpellAction;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.iota.*;
import at.petrak.hexcasting.api.casting.mishaps.*;
import com.iridium126.createmanaindustry.compat.ysm.YsmServerRuntime;
import net.minecraft.server.level.ServerPlayer;

/** Restores the target's selection from before the first edit in this session. */
public final class OpRestoreYsmModel implements SpellAction {
    @Override public int getArgc() { return 1; }
    @Override public Result execute(List<? extends Iota> args, CastingEnvironment env) throws Mishap {
        if (!(args.getFirst() instanceof EntityIota entity) || !(entity.getEntity(env.getWorld()) instanceof ServerPlayer player))
            throw MishapInvalidIota.ofType(args.getFirst(), 0, "entity.player");
        if (player.serverLevel() != env.getWorld()) throw new MishapYsm("Target player is in another dimension");
        env.assertVecInRange(player.position());
        try { YsmServerRuntime.get(env.getWorld().getServer()).overrides(); }
        catch (IllegalStateException failure) { throw new MishapYsm(failure.getMessage()); }
        return new Result(new Restore(player.getUUID()), 0, List.of(), 1);
    }
    private record Restore(UUID target) implements RenderedSpell {
        @Override public void cast(CastingEnvironment env) {
            ServerPlayer player = env.getWorld().getServer().getPlayerList().getPlayer(target);
            if (player != null) YsmServerRuntime.get(env.getWorld().getServer()).overrides().restore(player);
        }
    }
}
