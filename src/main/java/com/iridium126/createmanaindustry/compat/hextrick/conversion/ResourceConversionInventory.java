package com.iridium126.createmanaindustry.compat.hextrick.conversion;

import at.petrak.hexcasting.api.item.MediaHolderItem;
import dev.enjarai.trickster.item.KnotItem;
import dev.enjarai.trickster.item.component.ManaComponent;
import dev.enjarai.trickster.item.component.ModComponents;
import dev.enjarai.trickster.spell.mana.InfiniteManaPool;
import dev.enjarai.trickster.spell.mana.storage.ManaVariant;
import dev.enjarai.trickster.spell.mana.type.Manae;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/** Only loaded under the Hexcasting AND Trickster compatibility gate. */
public final class ResourceConversionInventory {
    private static final ManaVariant TRADITIONAL = ManaVariant.of(Manae.TRADITIONAL);

    private ResourceConversionInventory() {}

    private record ManaWrite(int slot, ManaComponent component) {}

    /** Clones are fully filled before any live item or source resource is changed. */
    public static final class ManaPlan {
        private final Container inventory;
        private final List<ManaWrite> writes;

        private ManaPlan(Container inventory, List<ManaWrite> writes) {
            this.inventory = inventory;
            this.writes = List.copyOf(writes);
        }

        public void apply() {
            for (ManaWrite write : writes) inventory.getItem(write.slot()).set(ModComponents.MANA, write.component());
            inventory.setChanged();
        }
    }

    public static ManaPlan prepareMana(ServerPlayer player, long amount) {
        return prepareMana(player.getInventory(), player.serverLevel(), amount);
    }

    public static ManaPlan prepareMana(Container inventory, ServerLevel level, long amount) {
        var writes = new ArrayList<ManaWrite>();
        long remaining = amount;
        boolean found = false;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof KnotItem)) continue;
            found = true;
            ManaComponent component = stack.get(ModComponents.MANA);
            if (component == null || component.pool() instanceof InfiniteManaPool) continue;
            var pool = component.pool();
            var variant = pool.getVariant(level);
            if (!variant.isBlank() && !variant.isOf(Manae.TRADITIONAL)) continue;
            long current = pool.get(level);
            long max = pool.getMax(level);
            if (current < 0 || max <= current) continue;
            long requested = Math.min(remaining, max - current);
            var clone = pool.makeClone(level);
            long leftover = clone.refill(TRADITIONAL, requested, level);
            if (leftover < 0 || leftover > requested)
                throw new ResourceConversionException("mana_capacity");
            long inserted = requested - leftover;
            if (inserted > 0) writes.add(new ManaWrite(i, component.with(clone)));
            remaining -= inserted;
            if (remaining == 0) return new ManaPlan(inventory, writes);
        }
        throw new ResourceConversionException(found ? "mana_capacity" : "no_knots");
    }

    public static void checkMediaCapacity(ServerPlayer player, long amount) {
        Container inventory = player.getInventory();
        long remaining = amount;
        boolean found = false;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof MediaHolderItem holder)) continue;
            found = true;
            if (!holder.canRecharge(stack)) continue;
            long inserted = holder.insertMedia(stack.copy(), remaining, true);
            if (inserted < 0 || inserted > remaining)
                throw new ResourceConversionException("media_capacity");
            remaining -= inserted;
            if (remaining == 0) return;
        }
        throw new ResourceConversionException(found ? "media_capacity" : "no_media_holders");
    }

    /** Slot replacements and the caller's mana debit share the same transaction. */
    public static void insertMedia(ServerPlayer player, long amount, TransactionContext transaction) {
        long remaining = amount;
        var slots = InventoryStorage.of(player.getInventory(), null).getSlots();
        for (var slot : slots) {
            ItemVariant original = slot.getResource();
            if (original.isBlank() || !(original.getItem() instanceof MediaHolderItem holder)) continue;
            long count = slot.getAmount();
            ItemStack updated = original.toStack((int) count);
            if (!holder.canRecharge(updated)) continue;
            long inserted = holder.insertMedia(updated, remaining, false);
            if (inserted < 0 || inserted > remaining)
                throw new ResourceConversionException("media_capacity");
            if (inserted == 0) continue;
            if (slot.extract(original, count, transaction) != count
                    || slot.insert(ItemVariant.of(updated), count, transaction) != count)
                throw new ResourceConversionException("media_capacity");
            remaining -= inserted;
            if (remaining == 0) return;
        }
        throw new ResourceConversionException("media_capacity");
    }
}
