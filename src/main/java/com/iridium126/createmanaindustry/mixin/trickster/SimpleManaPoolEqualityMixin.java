package com.iridium126.createmanaindustry.mixin.trickster;

import java.util.Objects;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.enjarai.trickster.spell.mana.SimpleManaPool;
import dev.enjarai.trickster.spell.mana.storage.ManaVariant;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Keeps charged knot components equal after stock snapshots and orders are decoded. */
@Mixin(value = SimpleManaPool.class, remap = false)
public abstract class SimpleManaPoolEqualityMixin {
    @Shadow protected ManaVariant variant;

    @ModifyExpressionValue(method = "equals(Ljava/lang/Object;)Z", at = @At(value = "FIELD",
            target = "Ldev/enjarai/trickster/spell/mana/SimpleManaPool;variant:Ldev/enjarai/trickster/spell/mana/storage/ManaVariant;",
            ordinal = 0), remap = false)
    private ManaVariant createmanaindustry$compareManaVariantByValue(ManaVariant otherVariant) {
        // The original expression is other.variant == this.variant. Reuse this
        // reference only when the decoded variant has the same value, retaining
        // the original empty-pool, amount, capacity and subclass comparisons.
        return Objects.equals(otherVariant, variant) ? variant : otherVariant;
    }
}
