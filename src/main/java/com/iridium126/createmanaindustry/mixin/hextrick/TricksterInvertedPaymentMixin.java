package com.iridium126.createmanaindustry.mixin.hextrick;

import com.iridium126.createmanaindustry.compat.trickster.*;
import dev.enjarai.trickster.spell.SpellContext;
import dev.enjarai.trickster.spell.exception.blunder.*;
import dev.enjarai.trickster.spell.mana.ManaPool;
import dev.enjarai.trickster.spell.mana.storage.ManaVariant;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import java.util.function.Predicate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SpellContext.class, remap = false)
public abstract class TricksterInvertedPaymentMixin {
    @Inject(method = "useScaledMana(Ljava/util/function/Predicate;DLnet/fabricmc/fabric/api/transfer/v1/transaction/TransactionContext;)V", at = @At("HEAD"), cancellable = true)
    private void cmi$use(Predicate<ManaVariant> predicate, double amount, TransactionContext transaction, CallbackInfo ci) {
        if (cmi$pay(amount, transaction, false)) ci.cancel();
    }
    @Inject(method = "checkScaledMana(Ljava/util/function/Predicate;DLnet/fabricmc/fabric/api/transfer/v1/transaction/TransactionContext;)V", at = @At("HEAD"), cancellable = true)
    private void cmi$check(Predicate<ManaVariant> predicate, double amount, TransactionContext transaction, CallbackInfo ci) {
        if (cmi$pay(amount, transaction, true)) ci.cancel();
    }
    @Inject(method = "checkScaledMana(Ldev/enjarai/trickster/spell/mana/storage/ManaVariant;DLnet/fabricmc/fabric/api/transfer/v1/transaction/TransactionContext;)V", at = @At("HEAD"), cancellable = true)
    private void cmi$checkVariant(ManaVariant variant, double amount, TransactionContext transaction, CallbackInfo ci) {
        if (cmi$pay(amount, transaction, true)) ci.cancel();
    }
    @Unique private boolean cmi$pay(double amount, TransactionContext transaction, boolean simulate) {
        if (!CastChains.inverted()) return false;
        SpellContext ctx = (SpellContext)(Object)this;
        var player = ctx.source().getPlayer().orElse(null);
        if (player == null || !CastChains.belongsTo(player.getUUID())) return false;
        long mana = (long)(amount * ManaPool.MANA_SCALE);
        if (mana > 0 && ((!simulate && !ctx.data().canUseMana()) || !InvertedPayments.spendMedia(player, mana, transaction, simulate)))
            throw new NotEnoughManaBlunder(mana);
        if (mana > 0 && !simulate) ((ChainState)ctx.state()).cmi$markResourceUse();
        return true;
    }
}
