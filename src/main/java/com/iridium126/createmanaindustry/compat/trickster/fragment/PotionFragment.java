package com.iridium126.createmanaindustry.compat.trickster.fragment;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import dev.enjarai.trickster.spell.Fragment;
import dev.enjarai.trickster.spell.fragment.FragmentType;
import io.wispforest.endec.Endec;
import io.wispforest.endec.StructEndec;
import io.wispforest.endec.impl.StructEndecBuilder;
import net.minecraft.core.Registry;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import java.io.*;
import java.util.*;

/** Immutable, complete persistent effect snapshot; every application receives a fresh instance. */
public final class PotionFragment implements Fragment {
    private static final long MAX_NBT_BYTES = 8L * 1024 * 1024;
    public static final StructEndec<PotionFragment> ENDEC = StructEndecBuilder.of(
            Endec.BYTES.fieldOf("effect", PotionFragment::getBytes), PotionFragment::new);
    // Deliberately independent of the Hexcasting-only fragment type holder.
    public static final FragmentType<PotionFragment> TYPE = Registry.register(FragmentType.REGISTRY,
            CreateManaIndustry.modLoc("potion"), new FragmentType<>(PotionFragment.class, ENDEC, OptionalInt.of(0x86b8d9)));
    private final CompoundTag snapshot;
    private final byte[] bytes;

    public PotionFragment(byte[] encoded) {
        if (encoded.length > MAX_NBT_BYTES) throw new IllegalArgumentException("Potion fragment exceeds NBT limit");
        try {
            snapshot = canonical(NbtIo.readCompressed(new ByteArrayInputStream(encoded), NbtAccounter.create(MAX_NBT_BYTES)));
            toEffect(); // Reject malformed and unknown registry values before using the fragment.
            bytes = encode(snapshot);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Invalid potion fragment", failure);
        }
    }
    private PotionFragment(CompoundTag snapshot) {
        this.snapshot = canonical(snapshot);
        bytes = encode(this.snapshot);
    }
    public static PotionFragment fromEffect(MobEffectInstance effect) {
        return new PotionFragment((CompoundTag)MobEffectInstance.CODEC.encodeStart(NbtOps.INSTANCE, effect).getOrThrow());
    }
    public MobEffectInstance toEffect() {
        return MobEffectInstance.CODEC.parse(NbtOps.INSTANCE, snapshot.copy()).getOrThrow();
    }
    public byte[] getBytes() { return bytes.clone(); }
    public FragmentType<?> type() { return TYPE; }
    public int getWeight() { return 8 + bytes.length; }
    public Component asText() {
        var effect = toEffect();
        Component duration = effect.isInfiniteDuration()
                ? Component.translatable("createmanaindustry.potion_fragment.infinite")
                : Component.translatable("createmanaindustry.potion_fragment.seconds", String.format(Locale.ROOT, "%.2f", effect.getDuration() / 20.0));
        return Component.translatable("createmanaindustry.potion_fragment.display",
                Component.translatable(effect.getEffect().value().getDescriptionId()), effect.getAmplifier() + 1, duration);
    }
    public boolean equals(Object other) { return other instanceof PotionFragment potion && snapshot.equals(potion.snapshot); }
    public int hashCode() { return snapshot.hashCode(); }
    private static byte[] encode(CompoundTag snapshot) {
        try {
            var out = new ByteArrayOutputStream();
            NbtIo.writeCompressed(snapshot, out);
            return out.toByteArray();
        } catch (IOException failure) { throw new IllegalArgumentException("Cannot encode potion fragment", failure); }
    }
    private static CompoundTag canonical(CompoundTag source) {
        var result = source.copy();
        for (String key : result.getAllKeys()) {
            if (result.get(key) instanceof CompoundTag child) result.put(key, canonical(child));
            // Cures are a set: registry IDs must have a stable order for value equality.
            else if (key.equals("neoforge:cures") && result.get(key) instanceof ListTag cures) {
                var entries = new ArrayList<Tag>(); cures.forEach(tag -> entries.add(tag.copy()));
                entries.sort(Comparator.comparing(Tag::toString));
                var sorted = new ListTag(); entries.forEach(sorted::add); result.put(key, sorted);
            }
        }
        return result;
    }
}
