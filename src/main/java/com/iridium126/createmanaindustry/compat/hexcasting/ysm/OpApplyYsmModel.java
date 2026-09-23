package com.iridium126.createmanaindustry.compat.hexcasting.ysm;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import at.petrak.hexcasting.api.casting.RenderedSpell;
import at.petrak.hexcasting.api.casting.castables.SpellAction;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.iota.*;
import at.petrak.hexcasting.api.casting.mishaps.*;
import com.iridium126.createmanaindustry.compat.hexcasting.HexCompat;
import com.iridium126.createmanaindustry.compat.ysm.YsmPreparedCache;
import com.iridium126.createmanaindustry.compat.ysm.YsmServerRuntime;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmResourceArchive;
import com.iridium126.createmanaindustry.config.ServerConfig;
import net.minecraft.server.level.ServerPlayer;

/** Validates a complete edit before media is checked; rendered effects stage it afterwards. */
public final class OpApplyYsmModel implements SpellAction {
    @Override public int getArgc() { return 2; }
    @Override public Result execute(List<? extends Iota> args, CastingEnvironment env) throws Mishap {
        if (!(args.get(0) instanceof EntityIota entity) || !(entity.getEntity(env.getWorld()) instanceof ServerPlayer target))
            throw MishapInvalidIota.ofType(args.get(0), 0, "entity.player");
        if (target.serverLevel() != env.getWorld()) throw new MishapYsm("Target player is in another dimension");
        env.assertVecInRange(target.position());
        if (!(args.get(1) instanceof ListIota list) || list.getList().isEmpty())
            throw MishapInvalidIota.ofType(args.get(1), 1, "list.group");
        var roots = new ArrayList<YsmGeometry.Group>();
        for (Iota iota : list.getList()) {
            if (!(iota instanceof GroupIota group)) throw MishapInvalidIota.ofType(iota, 1, "group");
            roots.add(group.value());
        }
        YsmResourceArchive archive;
        String existingModel = null;
        String sourceTexture = null;
        YsmResourceArchive sourceArchive = null;
        try {
            if (roots.getFirst().root() == null) throw new IllegalArgumentException("Expected a complete geometry file list");
            var runtime = YsmServerRuntime.get(env.getWorld().getServer());
            var source = runtime.require(roots.getFirst().root().snapshot());
            if (source.roots().equals(roots)) {
                existingModel = runtime.sourceModelId(source.digest()).orElseThrow(() -> new IllegalStateException("Source model is no longer loaded"));
                sourceTexture = source.defaultTexture();
                sourceArchive = source.archive();
                archive = null;
            } else {
                var prepared = runtime.prepare(roots);
                if (prepared.state() != YsmPreparedCache.State.READY) throw new MishapYsm(prepared.reason());
                archive = prepared.archive();
            }
        } catch (IllegalStateException | IllegalArgumentException failure) { throw new MishapYsm(failure.getMessage()); }
        long cost;
        try { cost = Math.multiplyExact((long) ServerConfig.ysmApplyCrystalUnits, HexCompat.getChargedCrystalMediaAmount()); }
        catch (ArithmeticException overflow) { throw new MishapYsm("Configured media cost is too large"); }
        return new Result(new Apply(target.getUUID(), archive, existingModel, sourceTexture, sourceArchive), cost, List.of(), 1);
    }
    private record Apply(UUID target, YsmResourceArchive archive, String existingModel, String sourceTexture,
            YsmResourceArchive sourceArchive) implements RenderedSpell {
        @Override public void cast(CastingEnvironment env) {
            ServerPlayer player = env.getWorld().getServer().getPlayerList().getPlayer(target);
            if (player != null && player.serverLevel() == env.getWorld()) {
                var manager = YsmServerRuntime.get(env.getWorld().getServer()).overrides();
                if (existingModel == null) manager.apply(player, archive);
                else manager.applyExisting(player, existingModel, sourceTexture, sourceArchive);
            }
        }
    }
}
