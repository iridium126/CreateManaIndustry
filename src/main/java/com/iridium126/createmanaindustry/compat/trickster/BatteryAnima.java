package com.iridium126.createmanaindustry.compat.trickster;

import com.iridium126.createmanaindustry.CMIComponents;
import com.iridium126.createmanaindustry.CMIFluids;
import com.iridium126.createmanaindustry.compat.hexcasting.HexCompat;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

public final class BatteryAnima {
    private BatteryAnima() {}
    public static boolean isBattery(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals("hexcasting:battery");
    }
    public static long get(ItemStack stack) { return Math.max(0, stack.getOrDefault(CMIComponents.ANIMA.get(), 0L)); }
    public static long capacity(ItemStack stack) {
        return isBattery(stack) ? AnimaMath.capacity(HexCompat.getMaxMedia(stack), HexCompat.getDustMediaAmount(),
                ServerConfig.batteryAnimaCapacityRatio) : 0;
    }
    public static int required(ItemStack stack, FluidStack fluid) {
        if (!isBattery(stack) || fluid.isEmpty() || !fluid.getFluid().isSame(CMIFluids.LIQUID_SOUL.get())) return -1;
        long space = capacity(stack) - get(stack);
        long amount = Math.min(Math.min(space / ServerConfig.animaPerBucket, 1000), fluid.getAmount());
        return amount > 0 ? (int) amount : -1;
    }
    public static ItemStack fill(ItemStack stack, int amount, FluidStack fluid) {
        if (amount <= 0 || amount > required(stack, fluid)) return ItemStack.EMPTY;
        ItemStack result = stack.copyWithCount(1);
        result.set(CMIComponents.ANIMA.get(), get(result) + (long)amount * ServerConfig.animaPerBucket);
        return result;
    }
    public static long available(ServerPlayer player) {
        long total = 0;
        for (int i=0; i<player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (isBattery(stack)) {
                long amount = get(stack);
                total = amount > Long.MAX_VALUE-total ? Long.MAX_VALUE : total+amount;
            }
        }
        return total;
    }
    public static boolean consume(ServerPlayer player, long amount) {
        if (amount < 0 || available(player) < amount) return false;
        for (int i=0; amount>0 && i<player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!isBattery(stack)) continue;
            long used = Math.min(get(stack), amount);
            if (used > 0) stack.set(CMIComponents.ANIMA.get(), get(stack)-used);
            amount -= used;
        }
        player.getInventory().setChanged();
        return true;
    }
}
