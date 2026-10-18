package com.iridium126.createmanaindustry.compat.trickster.casting;

import at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv;
import at.petrak.hexcasting.api.misc.DiscoveryHandlers;
import at.petrak.hexcasting.api.mod.HexConfig;
import at.petrak.hexcasting.api.utils.MediaHelper;
import at.petrak.hexcasting.common.lib.HexDamageTypes;
import com.iridium126.createmanaindustry.compat.hextrick.execution.MediaPaymentEffects;
import com.iridium126.createmanaindustry.compat.trickster.anima.AnimaMath;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import dev.enjarai.trickster.spell.mana.ManaPool;
import dev.enjarai.trickster.spell.mana.storage.PlayerInventoryManaStorage;
import dev.enjarai.trickster.spell.mana.type.Manae;
import java.util.*;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/** Exchanges casting costs only. Raw storage operations never re-enter the exchange hook. */
public final class InvertedPayments {
    private static final Map<ServerPlayer, MediaPayment> MEDIA = new WeakHashMap<>();
    private InvertedPayments() {
    }
    public static void logout(ServerPlayer player) {
        MEDIA.remove(player);
    }
    public static void stopped(MinecraftServer server) {
        MEDIA.keySet().removeIf(player -> player.getServer() == server);
    }
    public static long mediaToMana(long amount) {
        return AnimaMath.exchange(amount, (long)ServerConfig.manaPerBucket * ManaPool.MANA_SCALE, ServerConfig.mediaPerBucket);
    }
    public static long manaToMedia(long amount) {
        return AnimaMath.exchange(amount, ServerConfig.mediaPerBucket, (long)ServerConfig.manaPerBucket * ManaPool.MANA_SCALE);
    }
    public static boolean spendMana(ServerPlayer player, long media, boolean simulate) {
        if (player.isCreative() || media <= 0) return true;
        long wanted = mediaToMana(media), extracted = 0;
        try (var transaction = Transaction.openOuter()) {
            for (var view : new PlayerInventoryManaStorage(player).nonEmptyViews()) {
                if (!view.getResource().isOf(Manae.TRADITIONAL)) continue;
                extracted += view.extract(view.getResource(), wanted - extracted, transaction);
                if (extracted == wanted) break;
            }
            if (extracted != wanted) return false;
            if (!simulate) transaction.commit();
            return true;
        }
    }
    public static boolean spendMedia(ServerPlayer player, long mana, TransactionContext parent, boolean simulate) {
        if (player.isCreative() || mana <= 0) return true;
        return MEDIA.computeIfAbsent(player, MediaPayment::new).pay(manaToMedia(mana), parent, simulate);
    }
    private static final class RawStaff extends StaffCastEnv {
        RawStaff(ServerPlayer player) {
            super(player, InteractionHand.MAIN_HAND);
        }
        long inventory(long cost, boolean simulate) {
            for (var source : MediaHelper.scanPlayerForMediaStuff(caster)) {
                cost -= MediaHelper.extractMedia(source, cost, false, simulate);
                if (cost <= 0) break;
            }
            return cost;
        }
        boolean overcast() {
            return canOvercast();
        }
    }
    private record StackCopy(ItemStack target, ItemStack copy) {
    }
    private record Snapshot(List<StackCopy> stacks, long extracted, long wasted, long missing, float damage) {
    }
    private static final class MediaPayment extends SnapshotParticipant<Snapshot> {
        private final ServerPlayer player;
        private final RawStaff environment;
        private float pendingDamage;
        private long pendingExtracted;
        private long pendingWasted;
        private long pendingMissing;
        MediaPayment(ServerPlayer player) {
            this.player=player;
            environment=new RawStaff(player);
        }
        boolean pay(long cost, TransactionContext parent, boolean simulate) {
            long missing = environment.inventory(cost, true);
            float damage = missing > 0 ? (float)Math.max(missing / HexConfig.common().mediaToHealthRate(), 0.5) : 0;
            if (missing > 0 && (!environment.overcast()
                    || player.isInvulnerableTo(player.damageSources().source(HexDamageTypes.OVERCAST))
                    || player.getHealth() - pendingDamage < missing / HexConfig.common().mediaToHealthRate())) return false;
            if (simulate) return true;
            try (var transaction = Transaction.openNested(parent)) {
                updateSnapshots(transaction);
                long remaining = environment.inventory(cost, false);
                pendingDamage += damage;
                pendingExtracted = saturatedAdd(pendingExtracted, cost - remaining);
                pendingWasted = saturatedAdd(pendingWasted, Math.max(0, -remaining));
                pendingMissing = saturatedAdd(pendingMissing, Math.max(0, remaining));
                transaction.commit();
                return true;
            }
        }
        protected Snapshot createSnapshot() {
            var copies = new ArrayList<StackCopy>();
            for (int i=0; i<player.getInventory().getContainerSize(); i++) {
                ItemStack stack = player.getInventory().getItem(i);
                copies.add(new StackCopy(stack, stack.copy()));
            }
            for (ItemStack stack : DiscoveryHandlers.collectExtraEquipments(player)) copies.add(new StackCopy(stack, stack.copy()));
            return new Snapshot(copies, pendingExtracted, pendingWasted, pendingMissing, pendingDamage);
        }
        protected void readSnapshot(Snapshot snapshot) {
            for (StackCopy stack : snapshot.stacks()) {
                stack.target().setCount(stack.copy().getCount());
                // Restore the complete map, including defaults removed by a holder
                // and components which appeared only during the reservation.
                for (var type : Set.copyOf(stack.target().getComponents().keySet())) stack.target().remove(type);
                stack.target().applyComponents(stack.copy().getComponents());
            }
            pendingDamage = snapshot.damage();
            pendingExtracted = snapshot.extracted();
            pendingWasted = snapshot.wasted();
            pendingMissing = snapshot.missing();
        }
        protected void onFinalCommit() {
            float damage = pendingDamage;
            long extracted = pendingExtracted, wasted = pendingWasted, missing = pendingMissing;
            pendingDamage = 0;
            pendingExtracted = 0;
            pendingWasted = 0;
            pendingMissing = 0;
            MediaPaymentEffects.commit(player, extracted, wasted, missing, damage);
            player.getInventory().setChanged();
        }
        private static long saturatedAdd(long left, long right) { return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right; }
    }
}
