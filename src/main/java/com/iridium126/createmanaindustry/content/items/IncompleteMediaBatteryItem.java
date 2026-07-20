package com.iridium126.createmanaindustry.content.items;

import at.petrak.hexcasting.api.item.MediaHolderItem;
import at.petrak.hexcasting.api.utils.MediaHelper;
import at.petrak.hexcasting.common.lib.HexDataComponents;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.function.Supplier;

/**
 * Incomplete crafting intermediate for {@code ItemMediaBattery}.
 * <p>
 * Implements {@link MediaHolderItem} directly (not {@code HexHolderItem}) since
 * batteries are pure media containers with no hex pattern storage.
 * <p>
 * Entry point: Minecraft glass bottle filled with {@code liquid_media} via Spout.
 * Finalized via Mechanical Press into {@code hexcasting:battery}.
 */
public class IncompleteMediaBatteryItem extends Item implements MediaHolderItem {

    private final Supplier<Long> maxMediaSupplier;
    private final ResourceLocation finalItemId;

    public IncompleteMediaBatteryItem(Properties properties, Supplier<Long> maxMediaSupplier,
                                      ResourceLocation finalItemId) {
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

    /** The final Hexcasting battery item this turns into on pressing. */
    public ResourceLocation getFinalItemId() {
        return finalItemId;
    }

    /**
     * Ensures the stack has a {@link HexDataComponents#MEDIA_MAX} set, using the
     * default from config if absent. Called during initial conversion from a
     * glass bottle.
     */
    public void ensureMaxMedia(ItemStack stack) {
        if (!stack.has(HexDataComponents.MEDIA_MAX)) {
            stack.set(HexDataComponents.MEDIA_MAX, maxMediaSupplier.get());
        }
    }
}
