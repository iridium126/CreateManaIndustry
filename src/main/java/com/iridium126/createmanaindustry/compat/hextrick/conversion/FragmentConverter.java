package com.iridium126.createmanaindustry.compat.hextrick.conversion;

import at.petrak.hexcasting.api.casting.iota.*;
import com.iridium126.createmanaindustry.compat.hextrick.data.*;
import dev.enjarai.trickster.spell.Fragment;
import dev.enjarai.trickster.spell.SpellPart;
import dev.enjarai.trickster.spell.fragment.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

/** Registration-time conversions. Opaque values retain the existing wrapper semantics. */
public final class FragmentConverter {
    private static final Map<Class<?>, Function<Iota, Fragment>> TO_FRAGMENT = new LinkedHashMap<>();
    private static final Map<Class<?>, BiFunction<Fragment, ServerLevel, Iota>> TO_IOTA = new LinkedHashMap<>();
    private static boolean frozen;

    static {
        registerIota(DoubleIota.class, value -> {
            try { return new NumberFragment(value.getDouble()); }
            catch (RuntimeException invalid) { return wrap(value); }
        });
        registerIota(BooleanIota.class, value -> BooleanFragment.of(value.getBool()));
        registerIota(EntityIota.class, value -> new EntityFragment(value.getEntityId(), value.getEntityName() == null
                ? Component.translatable("hexcasting.spelldata.entity.whoknows") : value.getEntityName().copy()));
        registerIota(Vec3Iota.class, value -> {
            try {
                var vector = value.getVec3();
                return new VectorFragment(new Vector3d(vector.x, vector.y, vector.z));
            } catch (RuntimeException invalid) { return wrap(value); }
        });
        registerIota(NullIota.class, value -> VoidFragment.INSTANCE);
        registerIota(GarbageIota.class, value -> VoidFragment.INSTANCE);
        registerIota(ListIota.class, value -> {
            var values = new ArrayList<Fragment>();
            value.subIotas().forEach(child -> values.add(iotaToFragment(child)));
            return new ListFragment(values);
        });
        registerFragment(VoidFragment.class, (value, world) -> null);
        registerFragment(IotaFragment.class, (value, world) -> value.getIota());
        registerFragment(NumberFragment.class, (value, world) -> new DoubleIota(value.number()));
        registerFragment(BooleanFragment.class, (value, world) -> new BooleanIota(value.asBoolean()));
        registerFragment(VectorFragment.class, (value, world) -> new Vec3Iota(new Vec3(
                value.vector().x(), value.vector().y(), value.vector().z())));
        registerFragment(EntityFragment.class, (value, world) -> {
            return new EntityIota(value.uuid(), value.name().copy());
        });
        registerFragment(ListFragment.class, (value, world) -> new ListIota(FragmentStructures.children(value).stream()
                .map(child -> {
                    Iota converted = fragmentToIota(child, world);
                    return converted == null ? new NullIota() : converted;
                }).toList()));
        registerFragment(SpellPart.class, (value, world) -> new TrickIota(value));
    }

    private FragmentConverter() {}

    public static synchronized <I extends Iota> void registerIota(Class<I> type, Function<I, Fragment> converter) {
        if (frozen) throw new IllegalStateException("Conversion registration is closed");
        if (TO_FRAGMENT.putIfAbsent(type, value -> converter.apply(type.cast(value))) != null)
            throw new IllegalArgumentException("Duplicate iota conversion: " + type.getName());
    }

    public static synchronized <F extends Fragment> void registerFragment(Class<F> type, BiFunction<F, ServerLevel, Iota> converter) {
        if (frozen) throw new IllegalStateException("Conversion registration is closed");
        if (TO_IOTA.putIfAbsent(type, (value, world) -> converter.apply(type.cast(value), world)) != null)
            throw new IllegalArgumentException("Duplicate fragment conversion: " + type.getName());
    }

    public static synchronized void freeze() { frozen = true; }

    public static Fragment iotaToFragment(Iota value) {
        var converter = TO_FRAGMENT.get(value.getClass());
        if (converter != null) return converter.apply(value);
        for (var entry : TO_FRAGMENT.entrySet()) if (entry.getKey().isInstance(value)) return entry.getValue().apply(value);
        return wrap(value);
    }

    private static Fragment wrap(Iota value) { return new IotaFragment(IotaFragment.serializeIota(value)); }

    @Nullable public static Iota fragmentToIota(@Nullable Fragment value) { return fragmentToIota(value, null); }

    @Nullable public static Iota fragmentToIota(@Nullable Fragment value, @Nullable ServerLevel world) {
        if (value == null) return null;
        var converter = TO_IOTA.get(value.getClass());
        if (converter != null) return converter.apply(value, world);
        for (var entry : TO_IOTA.entrySet()) if (entry.getKey().isInstance(value)) return entry.getValue().apply(value, world);
        return new TrickIota(new SpellPart(value));
    }
}
