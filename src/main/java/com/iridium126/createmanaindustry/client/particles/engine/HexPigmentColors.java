package com.iridium126.createmanaindustry.client.particles.engine;

import at.petrak.hexcasting.api.pigment.ColorProvider;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.DyeColor;
import java.lang.reflect.Field;

/** CPU/GPU pigment bridge. Only exact built-in provider classes are adapted. */
public final class HexPigmentColors {
    public record Palette(int[] colors, float divisor) {
        public Palette { colors = colors.clone(); }
    }

    public static int sample(ColorProvider provider, float time, Vec3 position) {
        return provider.getColor(time, position);
    }

    public static float channel(int argb, int shift) {
        return ((argb >>> shift) & 255) / 255f;
    }

    /** Resolve once per provider change, never in the per-pattern render loop. */
    public static Palette describe(ColorProvider provider) {
        String name = provider.getClass().getName();
        String prefix = "at.petrak.hexcasting.common.items.pigment.";
        try {
            if (name.equals(prefix + "ItemAmethystPigment$MyColorProvider"))
                return new Palette(new int[]{0xffab65eb}, 0);
            if (name.equals(prefix + "ItemDyePigment$MyColorProvider")) {
                Object item = field(provider, "this$0");
                return new Palette(new int[]{((DyeColor) field(item, "dyeColor")).getTextColor()}, 0);
            }
            if (name.equals(prefix + "ItemAmethystAndCopperPigment$MyColorProvider"))
                return new Palette((int[]) field(provider, "COLORS"), 600);
            if (name.equals(prefix + "ItemUUIDPigment$MyColorProvider"))
                return nonempty((int[]) field(provider, "colors"), 400);
            if (name.equals(prefix + "ItemPridePigment$MyColorProvider"))
                return nonempty((int[]) field(field(field(provider, "this$0"), "type"), "colors"), 400);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Version changes and third-party overrides retain the real Java provider.
        }
        return null;
    }

    private static Palette nonempty(int[] colors, float divisor) {
        return colors.length == 0 ? null : new Palette(colors, divisor);
    }

    private static Object field(Object object, String name) throws ReflectiveOperationException {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private HexPigmentColors() {}
}
