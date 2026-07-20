package com.iridium126.createmanaindustry.content.items;

import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.item.HexHolderItem;
import at.petrak.hexcasting.api.pigment.FrozenPigment;
import at.petrak.hexcasting.api.utils.MediaHelper;
import at.petrak.hexcasting.common.lib.HexDataComponents;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Supplier;

/**
 * A unified incomplete hexcasting item — the crafting intermediate for
 * ItemCypher, ItemTrinket, and ItemArtifact in the Create processing pipeline.
 * <p>
 * Three instances are registered (one per target item type), differentiated by
 * their {@link #maxMediaSupplier} (from config) and {@link #finalItemId}.
 * <p>
 * Implements {@link HexHolderItem} so accumulated Iotas and media can be
 * stored in Hexcasting's own data components
 * ({@link HexDataComponents#HEX_HOLDER_PATTERNS},
 * {@link HexDataComponents#MEDIA}, etc.). The pressing step reads those
 * components and calls {@code writeHex()} on the final item.
 */
public class IncompleteHexItem extends Item implements HexHolderItem {

    private final Supplier<Long> maxMediaSupplier;
    private final ResourceLocation finalItemId;

    public IncompleteHexItem(Properties properties, Supplier<Long> maxMediaSupplier, ResourceLocation finalItemId) {
        super(properties);
        this.maxMediaSupplier = maxMediaSupplier;
        this.finalItemId = finalItemId;
    }

    // ---- MediaHolderItem ---------------------------------------------------

    @Override
    public long getMedia(ItemStack stack) {
        Long media = stack.get(HexDataComponents.MEDIA);
        return media != null ? media : 0L;
    }

    @Override
    public long getMaxMedia(ItemStack stack) {
        Long stored = stack.get(HexDataComponents.MEDIA_MAX);
        return stored != null ? stored : maxMediaSupplier.get();
    }

    @Override
    public void setMedia(ItemStack stack, long media) {
        long max = getMaxMedia(stack);
        stack.set(HexDataComponents.MEDIA, media < 0 ? 0 : Math.min(media, max));
    }

    @Override
    public boolean canProvideMedia(ItemStack stack) {
        return false;
    }

    @Override
    public boolean canRecharge(ItemStack stack) {
        return false;
    }

    // ---- HexHolderItem -----------------------------------------------------

    @Override
    public boolean canDrawMediaFromInventory(ItemStack stack) {
        return false;
    }

    @Override
    public boolean hasHex(ItemStack stack) {
        return stack.has(HexDataComponents.HEX_HOLDER_PATTERNS);
    }

    @Override
    public @Nullable List<Iota> getHex(ItemStack stack, ServerLevel level) {
        return stack.get(HexDataComponents.HEX_HOLDER_PATTERNS);
    }

    @Override
    public void writeHex(ItemStack stack, List<Iota> program, @Nullable FrozenPigment pigment, long media) {
        if (program != null) {
            stack.set(HexDataComponents.HEX_HOLDER_PATTERNS, program);
        }
        if (pigment != null) {
            stack.set(HexDataComponents.PIGMENT, pigment);
        }
        withMedia(stack, media, media);
    }

    @Override
    public void clearHex(ItemStack stack) {
        stack.remove(HexDataComponents.HEX_HOLDER_PATTERNS);
        stack.remove(HexDataComponents.PIGMENT);
        stack.remove(HexDataComponents.MEDIA);
        stack.remove(HexDataComponents.MEDIA_MAX);
    }

    @Override
    public @Nullable FrozenPigment getPigment(ItemStack stack) {
        return stack.get(HexDataComponents.PIGMENT);
    }

    // ---- Visual (durability bar) -------------------------------------------

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return getMaxMedia(stack) > 0;
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return MediaHelper.mediaBarColor(getMedia(stack), getMaxMedia(stack));
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        return MediaHelper.mediaBarWidth(getMedia(stack), getMaxMedia(stack));
    }

    // ---- Item-specific -----------------------------------------------------

    /** The final Hexcasting item this incomplete item turns into on pressing. */
    public ResourceLocation getFinalItemId() {
        return finalItemId;
    }

    // ---- Internal helpers --------------------------------------------------

    private static void withMedia(ItemStack stack, long media, long maxMedia) {
        stack.set(HexDataComponents.MEDIA, media);
        stack.set(HexDataComponents.MEDIA_MAX, maxMedia);
    }

    /**
     * Ensures the stack has a {@link HexDataComponents#MEDIA_MAX} set, using the
     * default from config if absent. Called during initial conversion from a
     * fresh item.
     */
    public void ensureMaxMedia(ItemStack stack) {
        if (!stack.has(HexDataComponents.MEDIA_MAX)) {
            stack.set(HexDataComponents.MEDIA_MAX, maxMediaSupplier.get());
        }
    }
}
