package com.iridium126.createmanaindustry.compat.trickster;

import java.util.Optional;

import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.xplat.IXplatAbstractions;
import dev.enjarai.trickster.spell.Fragment;
import dev.enjarai.trickster.spell.Pattern;
import dev.enjarai.trickster.spell.SpellContext;
import dev.enjarai.trickster.spell.exception.blunder.InvalidItemBlunder;
import dev.enjarai.trickster.spell.exception.blunder.NoPlayerBlunder;
import dev.enjarai.trickster.spell.exception.blunder.OutOfRangeBlunder;
import dev.enjarai.trickster.spell.fragment.FragmentType;
import dev.enjarai.trickster.spell.fragment.storage.SlotFragment;
import dev.enjarai.trickster.spell.fragment.storage.VariantType;
import dev.enjarai.trickster.spell.trick.Trick;
import dev.enjarai.trickster.spell.type.RetType;
import dev.enjarai.trickster.spell.type.Signature;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.world.item.ItemStack;

/**
 * Trickster trick: reads a Hexcasting {@link Iota} from an iota-holding item
 * in the given slot (defaulting to the caster's other hand) and produces an
 * {@link IotaFragment}. Modeled on Trickster's own {@code ReadSpellTrick}.
 * <p>
 * PLACEHOLDER pattern {@code (6, 7, 4, 1, 0, 3, 4, 5, 2, 1)} — verified statically
 * against all vendored Trickster patterns; confirm in-game (a registry
 * override warning in the log means a collision).
 */
public class ReadIotaTrick extends Trick<ReadIotaTrick> {

    public ReadIotaTrick() {
        super(Pattern.of(6, 7, 4, 1, 0, 3, 4, 5, 2, 1),
                Signature.of(FragmentType.SLOT.optionalOfArg(), ReadIotaTrick::run, RetType.ANY.optionalOfRet()));
    }

    public Optional<Fragment> run(SpellContext ctx, Optional<SlotFragment> optionalSlot) {
        var slot = optionalSlot.or(() -> ctx.source().getOtherHandSlot())
                .orElseThrow(NoPlayerBlunder::new);

        double range = ctx.source().getPos().distance(slot.getSourceOrCasterPos(ctx));
        if (range > 16) {
            throw new OutOfRangeBlunder(16.0, range);
        }

        // ItemVariant resolves from the loom-remap exported Mojang-mapped
        // fabric-api jar, so toStack() returns a Mojang ItemStack.
        ItemVariant variant = slot.getResource(ctx, VariantType.ITEM);
        ItemStack stack = variant.toStack();

        var holder = IXplatAbstractions.INSTANCE.findDataHolder(stack);
        Iota iota = holder == null ? null : holder.readIota();
        if (iota == null) {
            throw new InvalidItemBlunder();
        }

        byte[] bytes;
        try {
            bytes = IotaFragment.serializeIota(iota);
        } catch (RuntimeException e) {
            // Iota not serializable (e.g. oversized — degrades to garbage) — treat as invalid item.
            throw new InvalidItemBlunder();
        }
        return Optional.of(new IotaFragment(bytes));
    }
}
