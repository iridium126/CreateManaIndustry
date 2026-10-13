package com.iridium126.createmanaindustry.compat.trickster;

import at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv;
import at.petrak.hexcasting.api.casting.mishaps.Mishap;
import at.petrak.hexcasting.api.misc.DiscoveryHandlers;
import at.petrak.hexcasting.api.mod.HexConfig;
import at.petrak.hexcasting.common.lib.HexDamageTypes;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import dev.enjarai.trickster.spell.mana.ManaPool;
import dev.enjarai.trickster.spell.mana.storage.PlayerInventoryManaStorage;
import dev.enjarai.trickster.spell.mana.type.Manae;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** Exchanges casting costs only. Raw storage operations never re-enter the exchange hook. */
public final class InvertedPayments {
    private static final Map<ServerPlayer, MediaPayment> MEDIA = new WeakHashMap<>();
    private InvertedPayments() {}
    public static void logout(ServerPlayer player) { MEDIA.remove(player); }
    public static void stopped(net.minecraft.server.MinecraftServer server) {
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
        RawStaff(ServerPlayer player) { super(player, InteractionHand.MAIN_HAND); }
        long inventory(long cost, boolean simulate) { return extractMediaFromInventory(cost, false, simulate); }
        boolean overcast() { return canOvercast(); }
    }
    private record StackCopy(ItemStack target, ItemStack copy) {}
    private record Snapshot(List<StackCopy> stacks, float damage) {}
    private static final class MediaPayment extends SnapshotParticipant<Snapshot> {
        private final ServerPlayer player;
        private final RawStaff environment;
        private float pendingDamage;
        MediaPayment(ServerPlayer player) { this.player=player; environment=new RawStaff(player); }
        boolean pay(long cost, TransactionContext parent, boolean simulate) {
            long missing = environment.inventory(cost, true);
            float damage = missing > 0 ? (float)Math.max(missing / HexConfig.common().mediaToHealthRate(), 0.5) : 0;
            if (missing > 0 && (!environment.overcast() || player.isInvulnerableTo(player.damageSources().source(HexDamageTypes.OVERCAST))
                    || player.getHealth() - pendingDamage < missing / HexConfig.common().mediaToHealthRate())) return false;
            if (simulate) return true;
            try (var transaction = Transaction.openNested(parent)) {
                updateSnapshots(transaction);
                environment.inventory(cost, false);
                pendingDamage += damage;
                transaction.commit();
                return true;
            }
        }
        protected Snapshot createSnapshot() {
            var copies = new ArrayList<StackCopy>();
            for (int i=0; i<player.getInventory().getContainerSize(); i++) {
                ItemStack stack = player.getInventory().getItem(i); copies.add(new StackCopy(stack, stack.copy()));
            }
            for (ItemStack stack : DiscoveryHandlers.collectExtraEquipments(player)) copies.add(new StackCopy(stack, stack.copy()));
            return new Snapshot(copies, pendingDamage);
        }
        protected void readSnapshot(Snapshot snapshot) {
            for (StackCopy stack : snapshot.stacks()) {
                stack.target().setCount(stack.copy().getCount());
                stack.target().applyComponentsAndValidate(stack.copy().getComponentsPatch());
            }
            pendingDamage = snapshot.damage();
        }
        protected void onFinalCommit() {
            float damage = pendingDamage; pendingDamage = 0;
            if (damage > 0) Mishap.trulyHurt(player, player.damageSources().source(HexDamageTypes.OVERCAST), damage);
            player.getInventory().setChanged();
        }
    }
}
