package com.iridium126.createmanaindustry.compat.trickster;

import at.petrak.hexcasting.api.casting.iota.DoubleIota;
import at.petrak.hexcasting.api.casting.iota.EntityIota;
import at.petrak.hexcasting.api.casting.iota.GarbageIota;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import at.petrak.hexcasting.api.casting.mishaps.MishapOthersName;
import at.petrak.hexcasting.common.lib.hex.HexIotaTypes;
import com.iridium126.createmanaindustry.compat.hextrick.data.TrickIota;
import com.iridium126.createmanaindustry.compat.hextrick.data.IotaFragment;
import dev.enjarai.trickster.spell.SpellPart;
import dev.enjarai.trickster.spell.fragment.EntityFragment;
import dev.enjarai.trickster.spell.fragment.ListFragment;
import dev.enjarai.trickster.spell.fragment.MapFragment;
import dev.enjarai.trickster.spell.fragment.NumberFragment;
import dev.enjarai.trickster.spell.fragment.VectorFragment;
import io.netty.buffer.Unpooled;
import io.vavr.collection.HashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class HexTrickDataGameTests {
    @GameTest(template = "trickster_stock_test")
    public static void trickSnapshotOwnsNestedMutableData(GameTestHelper helper) {
        var vector = new Vector3d(1, 2, 3);
        var values = new ArrayList<dev.enjarai.trickster.spell.Fragment>(List.of(new VectorFragment(vector)));
        var root = new SpellPart(new ListFragment(values));
        var iota = new TrickIota(root);
        byte[] encoded = iota.getSpell().toBytes();
        int hash = iota.hashCode();
        vector.set(4, 5, 6);
        values.clear();
        root.glyph = new NumberFragment(99);
        SpellPart exposed = iota.getSpell();
        ((Vector3d) ((VectorFragment) ((ListFragment) exposed.glyph).fragments().getFirst()).vector()).set(7, 8, 9);
        exposed.glyph = new NumberFragment(100);
        helper.assertTrue(Arrays.equals(encoded, iota.getSpell().toBytes()) && iota.hashCode() == hash,
                "A mutable input or getter changed the stored TrickIota");
        helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void iotaFragmentOwnsEncodedBytes(GameTestHelper helper) {
        byte[] bytes = IotaFragment.serializeIota(new DoubleIota(42));
        var fragment = new IotaFragment(bytes);
        int hash = fragment.hashCode();
        Arrays.fill(bytes, (byte) 0);
        Arrays.fill(fragment.getBytes(), (byte) 0);
        helper.assertTrue(fragment.hashCode() == hash && fragment.getIota().equals(new DoubleIota(42)),
                "IotaFragment retained a mutable byte array");
        helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void nestedGlyphAndListPayloadsCountTowardHexLimits(GameTestHelper helper) {
        var entries = new ArrayList<dev.enjarai.trickster.spell.Fragment>();
        for (int i = 0; i < HexIotaTypes.MAX_SERIALIZATION_TOTAL; i++) entries.add(new NumberFragment(i));
        var large = new TrickIota(new SpellPart(new SpellPart(new ListFragment(entries))));
        helper.assertTrue(IotaType.isTooLargeToSerialize(List.of(large)),
                "Nested glyph/list payload bypassed the Hex size limit");
        var tag = IotaType.TYPED_CODEC.encodeStart(NbtOps.INSTANCE, large).getOrThrow();
        helper.assertTrue(IotaType.TYPED_CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow() instanceof GarbageIota,
                "Oversized TrickIota did not degrade on the NBT path");
        var shallow = new TrickIota(new SpellPart(new NumberFragment(1)));
        var nested = new TrickIota(new SpellPart(new SpellPart(new NumberFragment(1))));
        helper.assertTrue(nested.size() > shallow.size() && nested.depth() > shallow.depth(),
                "A spell stored in a glyph has no serialization size/depth");
        SpellPart deep = new SpellPart(new NumberFragment(1));
        // Stay inside the bridge's 256-node graph-depth bound while reaching
        // Hex's independent inclusive serialization-depth rejection boundary.
        for (int i = 0; i < HexIotaTypes.MAX_SERIALIZATION_DEPTH - 2; i++) {
            deep = new SpellPart(new NumberFragment(1), List.of(deep));
        }
        var deepIota = new TrickIota(deep);
        helper.assertTrue(deepIota.size() < HexIotaTypes.MAX_SERIALIZATION_TOTAL
                        && IotaType.isTooLargeToSerialize(List.of(deepIota)),
                "A small but deeply nested spell bypassed the Hex depth limit");
        helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void trueNamesInsideNativeAndWrappedFragmentsAreVisible(GameTestHelper helper) {
        UUID foreign = UUID.randomUUID();
        var entity = new EntityFragment(foreign, Component.literal("foreign player"));
        var wrapped = new IotaFragment(IotaFragment.serializeIota(new EntityIota(foreign, null, true)));
        for (var reference : List.of(entity, wrapped)) {
            var map = new MapFragment(HashMap.of(new NumberFragment(1), new ListFragment(List.of(reference))));
            var iota = new TrickIota(new SpellPart(new SpellPart(map)));
            helper.assertTrue(MishapOthersName.getTrueNameMishapFromDatum(helper.getLevel(), iota, null) != null,
                    "TrickIota hid a foreign true name from Hex's persistence checks");
        }
        helper.assertTrue(MishapOthersName.getTrueNameMishapFromDatum(helper.getLevel(),
                new TrickIota(new SpellPart(new NumberFragment(42))), null) == null,
                "A scalar spell was incorrectly treated as a true name");
        helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void networkAndNbtKeepSpellDataAndMetrics(GameTestHelper helper) {
        var original = new TrickIota(new SpellPart(new SpellPart(new ListFragment(List.of(new NumberFragment(42))))));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            IotaType.TYPED_STREAM_CODEC.encode(buffer, original);
            var decoded = IotaType.TYPED_STREAM_CODEC.decode(buffer);
            helper.assertTrue(original.equals(decoded) && original.size() == decoded.size()
                    && original.depth() == decoded.depth(), "Network round trip changed the spell or its metrics");
        } finally {
            buffer.release();
        }
        var tag = IotaType.TYPED_CODEC.encodeStart(NbtOps.INSTANCE, original).getOrThrow();
        var decoded = IotaType.TYPED_CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
        helper.assertTrue(original.equals(decoded) && original.size() == decoded.size()
                && original.depth() == decoded.depth(), "NBT round trip changed the spell or its metrics");
        var corrupt = new CompoundTag();
        corrupt.putString("type", "createmanaindustry:trick");
        corrupt.putString("value", new SpellPart(new IotaFragment(new byte[]{1, 2, 3})).toBase64());
        helper.assertTrue(IotaType.TYPED_CODEC.parse(NbtOps.INSTANCE, corrupt).getOrThrow() instanceof GarbageIota,
                "Corrupt nested iota bytes escaped the NBT codec's error result");
        helper.succeed();
    }

    private HexTrickDataGameTests() {}
}
