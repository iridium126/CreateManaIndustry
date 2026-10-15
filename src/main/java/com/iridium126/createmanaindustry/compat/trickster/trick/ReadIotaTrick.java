package com.iridium126.createmanaindustry.compat.trickster.trick;

import com.iridium126.createmanaindustry.compat.trickster.fragment.IotaFragment;

import java.util.Optional;

import com.iridium126.createmanaindustry.compat.hexcasting.FragmentConverter;

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
 * in the given slot (defaulting to the caster's other hand) and converts it to
 * the corresponding Trickster fragment, falling back to {@link IotaFragment}
 * when needed. Modeled on Trickster's own {@code ReadSpellTrick}.
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
        if(com.iridium126.createmanaindustry.compat.trickster.partition.PartitionManager.crosses(ctx.source().getWorld(),com.iridium126.createmanaindustry.compat.trickster.partition.PartitionFees.vec(ctx.source().getPos()),com.iridium126.createmanaindustry.compat.trickster.partition.PartitionFees.vec(slot.getSourceOrCasterPos(ctx))))range=Double.POSITIVE_INFINITY;
        if(ctx.source().getPlayer().map(p -> com.iridium126.createmanaindustry.compat.trickster.casting.CastChains.invertedFor(p.getUUID())).orElse(false))range=range<=16?Math.nextUp(16D):16D;
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
            return Optional.of(FragmentConverter.iotaToFragment(iota));
        } catch (RuntimeException conversionFailure) {
            try {
                return Optional.of(new IotaFragment(IotaFragment.serializeIota(iota)));
            } catch (RuntimeException serializationFailure) {
                throw new InvalidItemBlunder();
            }
        }
    }
}
