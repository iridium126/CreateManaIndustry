package com.iridium126.createmanaindustry.compat.trickster.trick;

import com.iridium126.createmanaindustry.compat.trickster.blunder.ResourceConversionBlunder;
import com.iridium126.createmanaindustry.compat.trickster.construct.ConstructMediaStorage;

import com.iridium126.createmanaindustry.compat.hexcasting.ConversionTickTime;
import com.iridium126.createmanaindustry.compat.hexcasting.ResourceConversionException;
import com.iridium126.createmanaindustry.compat.hexcasting.ResourceConversionInventory;
import com.iridium126.createmanaindustry.compat.hexcasting.ResourceConversionMath;
import dev.enjarai.trickster.spell.Pattern;
import dev.enjarai.trickster.spell.SpellContext;
import dev.enjarai.trickster.spell.execution.source.BlockSpellSource;
import dev.enjarai.trickster.spell.fragment.FragmentType;
import dev.enjarai.trickster.spell.fragment.NumberFragment;
import dev.enjarai.trickster.spell.mana.ManaPool;
import dev.enjarai.trickster.spell.trick.Trick;
import dev.enjarai.trickster.spell.type.Signature;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;

public final class ManaToMediaTrick extends Trick<ManaToMediaTrick> {
    public ManaToMediaTrick() {
        super(Pattern.of(3, 0, 4, 2), Signature.of(FragmentType.NUMBER, ManaToMediaTrick::run, FragmentType.NUMBER));
    }

    public NumberFragment run(SpellContext ctx, NumberFragment number) {
        ResourceConversionMath.Amounts amounts;
        try {
            amounts = ResourceConversionMath.manaToMedia(number.number(),
                    ConversionTickTime.milliseconds(ctx.source().getWorld().getServer()), ManaPool.MANA_SCALE);
        } catch (IllegalArgumentException e) {
            throw new ResourceConversionBlunder("invalid_amount");
        }
        try {
            if (ctx.source() instanceof BlockSpellSource<?> blockSource
                    && ConstructMediaStorage.isConstruct(blockSource.blockEntity)) {
                var construct = blockSource.blockEntity;
                ConstructMediaStorage.checkConversionCapacity(construct, amounts.output());
                try (var transaction = Transaction.openOuter()) {
                    ConstructMediaStorage.insertConvertedMedia(construct, amounts.output(), transaction);
                    ctx.useScaledMana(number.number(), transaction);
                    transaction.commit();
                }
                return new NumberFragment((double) amounts.output() / 10000.0);
            }
            var player = ctx.source().getPlayer().orElseThrow(() -> new ResourceConversionBlunder("no_player"));
            ResourceConversionInventory.checkMediaCapacity(player, amounts.output());
            try (var transaction = Transaction.openOuter()) {
                ResourceConversionInventory.insertMedia(player, amounts.output(), transaction);
                // Pass displayed points: useScaledMana multiplies by MANA_SCALE itself.
                ctx.useScaledMana(number.number(), transaction);
                transaction.commit();
            }
            player.getInventory().setChanged();
            player.inventoryMenu.broadcastChanges();
        } catch (ResourceConversionException e) {
            throw new ResourceConversionBlunder(e.reason());
        }
        return new NumberFragment((double) amounts.output() / 10000.0);
    }
}
