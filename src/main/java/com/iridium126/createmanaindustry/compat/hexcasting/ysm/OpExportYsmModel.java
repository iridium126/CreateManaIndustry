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
import com.iridium126.createmanaindustry.compat.ysm.YsmChatMessages;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmResourceArchive;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import net.minecraft.server.level.ServerPlayer;

/** Creates a complete plaintext archive, then sends an approved export job to the caster's client. */
public final class OpExportYsmModel implements SpellAction {
    @Override public int getArgc() { return 1; }
    @Override public Result execute(List<? extends Iota> args, CastingEnvironment env) throws Mishap {
        if (!(env.getCastingEntity() instanceof ServerPlayer caster) || caster.hasDisconnected())
            throw new MishapYsm("A connected player client is required for YSM export");
        if (!(args.getFirst() instanceof ListIota list) || list.getList().isEmpty())
            throw MishapInvalidIota.ofType(args.getFirst(), 0, "list.group");
        var rootKeys = new ArrayList<String>();
        for (Iota iota : list.getList()) {
            if (!(iota instanceof GroupIota group)) throw MishapInvalidIota.ofType(iota, 0, "group");
            rootKeys.add(group.key());
        }
        var runtime = YsmServerRuntime.get(env.getWorld().getServer());
        List<YsmGeometry.Group> roots;
        try { roots = runtime.references().materializeGroups(rootKeys); }
        catch (IllegalArgumentException | IllegalStateException failure) { throw new MishapYsm(failure.getMessage()); }
        YsmResourceArchive archive;
        try {
            if (roots.getFirst().root() == null) throw new IllegalArgumentException("Expected a complete geometry file list");
            if (!runtime.transfers().canQueue()) throw new IllegalStateException("YSM export queue is full; retry later");
            var source = runtime.require(roots.getFirst().root().snapshot());
            if (source.roots().equals(roots)) archive = source.archive();
            else {
                var result = runtime.prepare(roots);
                if (result.state() != YsmPreparedCache.State.READY) throw new MishapYsm(result.reason());
                archive = result.archive();
            }
        } catch (IllegalArgumentException | IllegalStateException failure) { throw new MishapYsm(failure.getMessage()); }
        long cost;
        try { cost = Math.multiplyExact((long) ServerConfig.ysmExportCrystalUnits, HexCompat.getChargedCrystalMediaAmount()); }
        catch (ArithmeticException overflow) { throw new MishapYsm("Configured media cost is too large"); }
        return new Result(new Export(caster.getUUID(), archive), cost, List.of(), 1);
    }
    private record Export(UUID caster, YsmResourceArchive archive) implements RenderedSpell {
        @Override public void cast(CastingEnvironment env) {
            var server = env.getWorld().getServer();
            ServerPlayer player = server.getPlayerList().getPlayer(caster);
            if (player != null && !player.hasDisconnected()) {
                try { YsmServerRuntime.get(server).transfers().export(player, archive); }
                catch (IllegalStateException failure) { player.sendSystemMessage(YsmChatMessages.exportFailed(failure.getMessage())); }
            }
        }
    }
}
