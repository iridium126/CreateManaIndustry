package com.iridium126.createmanaindustry.content.items;

import com.iridium126.createmanaindustry.trickster.TricksterKnotUtils;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.IItemHandler;

public class TricksterKnotItemHandler implements IItemHandler {
    public enum Mode {
        ALL_SLOTS,
        FIRST_SLOT
    }

    private final Container container;
    private final Mode mode;

    public static IItemHandler create(BlockEntity blockEntity, Mode mode) {
        return blockEntity instanceof Container container ? new TricksterKnotItemHandler(container, mode) : null;
    }

    private TricksterKnotItemHandler(Container container, Mode mode) {
        this.container = container;
        this.mode = mode;
    }

    @Override
    public int getSlots() {
        return switch (mode) {
            case ALL_SLOTS -> container.getContainerSize();
            case FIRST_SLOT -> container.getContainerSize() > 0 ? 1 : 0;
        };
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        if (!isSlotInRange(slot))
            return ItemStack.EMPTY;

        ItemStack stack = container.getItem(toContainerSlot(slot));
        return isKnot(stack) ? stack : ItemStack.EMPTY;
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (!isSlotInRange(slot) || stack.isEmpty() || !isKnot(stack))
            return stack;

        int containerSlot = toContainerSlot(slot);
        if (!container.getItem(containerSlot).isEmpty())
            return stack;

        int inserted = Math.min(stack.getCount(), Math.min(getSlotLimit(slot), stack.getMaxStackSize()));
        if (inserted <= 0)
            return stack;

        if (!simulate)
            container.setItem(containerSlot, copyWithCount(stack, inserted));

        if (inserted == stack.getCount())
            return ItemStack.EMPTY;

        ItemStack remainder = stack.copy();
        remainder.shrink(inserted);
        return remainder;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (!isSlotInRange(slot) || amount <= 0)
            return ItemStack.EMPTY;

        int containerSlot = toContainerSlot(slot);
        ItemStack stored = container.getItem(containerSlot);
        if (!isKnot(stored))
            return ItemStack.EMPTY;

        int extracted = Math.min(amount, stored.getCount());
        ItemStack result = copyWithCount(stored, extracted);
        if (!simulate) {
            ItemStack remaining = stored.copy();
            remaining.shrink(extracted);
            container.setItem(containerSlot, remaining);
        }
        return result;
    }

    @Override
    public int getSlotLimit(int slot) {
        return isSlotInRange(slot) ? 64 : 0;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return isSlotInRange(slot) && isKnot(stack);
    }

    private int toContainerSlot(int slot) {
        return slot;
    }

    private boolean isSlotInRange(int slot) {
        return slot >= 0 && slot < getSlots();
    }

    private boolean isKnot(ItemStack stack) {
        return TricksterKnotUtils.isKnotStack(stack);
    }

    private ItemStack copyWithCount(ItemStack stack, int count) {
        ItemStack copy = stack.copy();
        copy.setCount(count);
        return copy;
    }
}
