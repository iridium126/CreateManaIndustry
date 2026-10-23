package com.iridium126.createmanaindustry.compat.hextrick.execution;

import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.ListIota;
import at.petrak.hexcasting.xplat.IXplatAbstractions;
import com.iridium126.createmanaindustry.compat.hextrick.conversion.FragmentConverter;
import com.iridium126.createmanaindustry.compat.hextrick.data.IotaFragment;
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
import java.util.Optional;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.world.item.ItemStack;

/**
 * Trickster trick: reads a Hexcasting {@link Iota} from an iota-holding item
 * in the given slot (defaulting to the caster's other hand) and converts it to
 * the corresponding Trickster fragment. Lists are preserved whole in an
 * {@link IotaFragment} for Hexcasting's display and direct evaluation; other
 * unmapped values use the same wrapper. Modeled on Trickster's own {@code ReadSpellTrick}.
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

        double range = CastingRanges.reach(
                ctx.source(), ctx.source().getPos(), slot.getSourceOrCasterPos(ctx),
                ctx.source().getPos().distance(slot.getSourceOrCasterPos(ctx)));
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

        try {
            if (!(iota instanceof ListIota)) {
                try {
                    return Optional.of(FragmentConverter.iotaToFragment(iota));
                } catch (RuntimeException conversionFailure) {
                    // Keep an unmappable value intact if Hex can still serialize it.
                }
            }
            return Optional.of(new IotaFragment(IotaFragment.serializeIota(iota)));
        } catch (RuntimeException serializationFailure) {
            throw new InvalidItemBlunder();
        }
    }
}
