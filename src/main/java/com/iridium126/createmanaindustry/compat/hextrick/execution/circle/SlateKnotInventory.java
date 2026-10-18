package com.iridium126.createmanaindustry.compat.hextrick.execution.circle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * A read/write view over the knot slots of all slates that a hex circle has
 * reached. Exposes one slot per slate; mutations are pushed back into the
 * slates and synced to clients.
 */
public final class SlateKnotInventory implements Container {

    private final ServerLevel level;
    private final List<SlateKnotSlot> slots;
    private final List<ItemStack> syncedStacks;

    public static SlateKnotInventory forCircle(ServerLevel level, Collection<BlockPos> positions) {
        List<BlockPos> sortedPositions = new ArrayList<>(positions);
        sortedPositions.sort(Comparator.comparingLong(BlockPos::asLong));

        return forOrderedPositions(level, sortedPositions);
    }

    public static SlateKnotInventory forOrderedPositions(ServerLevel level, Collection<BlockPos> positions) {
        List<SlateKnotSlot> slots = new ArrayList<>();
        for (BlockPos pos : positions) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof SlateKnotHolder holder && !holder.createmanaindustry$getKnot().isEmpty()) {
                slots.add(new SlateKnotSlot(blockEntity, holder));
            }
        }
        return new SlateKnotInventory(level, slots);
    }

    public List<BlockPos> positions() {
        return slots.stream().map(slot -> slot.blockEntity().getBlockPos().immutable()).toList();
    }

    private SlateKnotInventory(ServerLevel level, List<SlateKnotSlot> slots) {
        this.level = level;
        this.slots = slots;
        this.syncedStacks = copyStacks(slots);
    }

    @Override
    public int getContainerSize() {
        return slots.size();
    }

    @Override
    public boolean isEmpty() {
        for (int slot = 0; slot < slots.size(); slot++) {
            if (!getItem(slot).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        if (!isLiveSlot(slot)) {
            return ItemStack.EMPTY;
        }
        return slots.get(slot).holder().createmanaindustry$getKnot();
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        if (!isLiveSlot(slot) || amount <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = getItem(slot);
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack removed = stack.split(amount);
        if (stack.isEmpty()) {
            slots.get(slot).holder().createmanaindustry$setKnot(ItemStack.EMPTY);
        }
        syncChangedSlots();
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        if (!isLiveSlot(slot)) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = getItem(slot);
        slots.get(slot).holder().createmanaindustry$setKnot(ItemStack.EMPTY);
        return stack;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (!isLiveSlot(slot)) {
            return;
        }
        slots.get(slot).holder().createmanaindustry$setKnot(stack);
        syncChangedSlots();
    }

    @Override
    public void setChanged() {
        syncChangedSlots();
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        for (int slot = 0; slot < slots.size(); slot++) {
            if (isLiveSlot(slot)) slots.get(slot).holder().createmanaindustry$setKnot(ItemStack.EMPTY);
        }
        syncChangedSlots();
    }

    public void syncChangedSlots() {
        for (int i = 0; i < slots.size(); i++) {
            if (!isLiveSlot(i)) continue;
            ItemStack current = slots.get(i).holder().createmanaindustry$getKnot();
            if (!ItemStack.matches(syncedStacks.get(i), current)) {
                sync(slots.get(i).blockEntity());
                syncedStacks.set(i, current.copy());
            }
        }
    }

    // Keep the BlockPos overload: Allvr resolves loaded cubes using all three coordinates.
    @SuppressWarnings("deprecation")
    private boolean isLiveSlot(int slot) {
        if (slot < 0 || slot >= slots.size()) return false;
        BlockEntity block = slots.get(slot).blockEntity();
        return !block.isRemoved() && level.hasChunkAt(block.getBlockPos())
                && level.getBlockEntity(block.getBlockPos()) == block;
    }

    private void sync(BlockEntity blockEntity) {
        blockEntity.setChanged();
        level.sendBlockUpdated(blockEntity.getBlockPos(), blockEntity.getBlockState(), blockEntity.getBlockState(), 3);
    }

    private static List<ItemStack> copyStacks(List<SlateKnotSlot> slots) {
        List<ItemStack> stacks = new ArrayList<>(slots.size());
        for (SlateKnotSlot slot : slots) {
            stacks.add(slot.holder().createmanaindustry$getKnot().copy());
        }
        return stacks;
    }

    private record SlateKnotSlot(BlockEntity blockEntity, SlateKnotHolder holder) {
    }
}
