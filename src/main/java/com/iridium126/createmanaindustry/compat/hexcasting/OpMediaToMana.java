package com.iridium126.createmanaindustry.compat.hexcasting;

import at.petrak.hexcasting.api.casting.castables.SpellAction;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.env.CircleCastEnv;
import com.iridium126.createmanaindustry.compat.hexcasting.circle.SlateKnotInventory;
import at.petrak.hexcasting.api.casting.iota.DoubleIota;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.mishaps.Mishap;
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota;
import dev.enjarai.trickster.spell.mana.ManaPool;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;

public final class OpMediaToMana implements SpellAction {
    public static final OpMediaToMana INSTANCE = new OpMediaToMana();

    private OpMediaToMana() {}

    @Override
    public int getArgc() { return 1; }

    @Override
    public Result execute(List<? extends Iota> args, CastingEnvironment env) throws Mishap {
        if (!(args.getFirst() instanceof DoubleIota number))
            throw MishapInvalidIota.ofType(args.getFirst(), 0, "double");
        ResourceConversionMath.Amounts amounts;
        try {
            amounts = ResourceConversionMath.mediaToMana(number.getDouble(),
                    ConversionTickTime.milliseconds(env.getWorld().getServer()), ManaPool.MANA_SCALE);
        } catch (IllegalArgumentException e) {
            throw new MishapResourceConversion("invalid_amount");
        }
        try {
            ResourceConversionInventory.ManaPlan plan;
            Runnable sync = () -> {};
            // Bound circles also use their reached slates, never the owner's inventory.
            if (env instanceof CircleCastEnv circle) {
                plan = ResourceConversionInventory.prepareMana(
                        SlateKnotInventory.forCircle(env.getWorld(), circle.circleState().reachedPositions),
                        env.getWorld(), amounts.output());
            } else if (env.getCastingEntity() instanceof ServerPlayer player) {
                plan = ResourceConversionInventory.prepareMana(player, amounts.output());
                sync = player.inventoryMenu::broadcastChanges;
            } else {
                throw new MishapResourceConversion("no_player");
            }
            Runnable afterCast = sync;
            return new Result(castingEnv -> {
                plan.apply();
                afterCast.run();
            }, amounts.cost(), List.of(), 1L);
        } catch (ResourceConversionException e) {
            throw new MishapResourceConversion(e.reason());
        }
    }
}
