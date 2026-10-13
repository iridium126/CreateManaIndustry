package com.iridium126.createmanaindustry.compat.hexcasting;

import java.util.ArrayList;
import java.util.List;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;

import at.petrak.hexcasting.api.casting.eval.CastResult;
import at.petrak.hexcasting.api.casting.eval.ResolvedPatternType;
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM;
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import at.petrak.hexcasting.api.casting.iota.ListIota;
import at.petrak.hexcasting.api.utils.TreeList;
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds;
import dev.enjarai.trickster.spell.Fragment;
import dev.enjarai.trickster.spell.SpellPart;
import net.minecraft.ChatFormatting;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.NotNull;

/**
 * Hexcasting iota that stores a full Trickster {@link SpellPart} spell
 * fragment tree. Serialization reuses Trickster's own compact wire format
 * ({@link Fragment#toBase64()} / {@link Fragment#fromBase64(String)}) so the
 * spell round-trips losslessly without depending on Trickster's endec/owo
 * libraries.
 * <p>
 * Displayed inline via {@link InlineTrickData}, rendered with Trickster's own
 * {@code CircleRenderer} so the glyph stack looks exactly like a Trickster
 * spell fragment tooltip.
 */
public class TrickIota extends Iota {

    private final SpellPart spell;

    public TrickIota(SpellPart spell) {
        super(() -> CMIHexIotaTypes.TRICK.get());
        // SpellPart is mutable — hold a defensive copy so hashCode/equals stay stable.
        this.spell = spell.deepClone();
    }

    public SpellPart getSpell() {
        return spell;
    }

    @Override
    public boolean executable() {
        return true;
    }

    /**
     * Runs the stored spell, consuming an optional argument list at the top of
     * the VM stack. Hermes has already removed this iota before calling here,
     * so its input order is {@code [arguments], trick, Hermes}. This also works
     * when the iota is executed unescaped inside a Hex list.
     */
    @Override
    public @NotNull CastResult execute(CastingVM vm, ServerLevel world, SpellContinuation continuation) {
        CastingImage image = vm.getImage();
        TreeList<Iota> stack = image.getStack();
        List<Iota> params = new ArrayList<>();
        if (!stack.isEmpty() && stack.last() instanceof ListIota list) {
            params.addAll(list.getList());
            stack = stack.init();
        }

        SpellExecutionBridge.SpellExecutionResult execution = SpellExecutionBridge.tryExecute(spell, params, vm.getEnv());
        if (execution.status() == SpellExecutionBridge.SpellExecutionStatus.COMPLETED) {
            if (execution.result() != null) {
                stack = stack.appended(execution.result());
            }
        } else if (execution.status() == SpellExecutionBridge.SpellExecutionStatus.FAILED) {
            CreateManaIndustry.LOGGER.warn("Failed to execute Trickster spell fragment");
        }

        CastingImage nextImage = image.copy(stack, image.getParenCount(), image.getParenthesized(),
                image.getEscapeNext(), image.getSimulateNext(), image.getOpsConsumed() + 1, image.getUserData());
        return new CastResult(this, continuation, nextImage, List.of(),
                ResolvedPatternType.EVALUATED, HexEvalSounds.NORMAL_EXECUTE.get());
    }

    @Override
    public boolean isTruthy() {
        return !spell.isEmpty();
    }

    @Override
    protected boolean toleratesOther(Iota that) {
        return typesMatch(this, that)
                && that instanceof TrickIota other
                && this.spell.equals(other.spell);
    }

    @Override
    public int hashCode() {
        return spell.hashCode();
    }

    /**
     * Node count of the spell tree, so {@code IotaType.TYPED_CODEC}'s
     * serialisation size guard degrades oversized spells to GarbageIota on the
     * NBT/codec path instead of failing.
     */
    @Override
    public int size() {
        return countNodes(spell);
    }

    private static int countNodes(SpellPart part) {
        int n = 1;
        for (SpellPart sub : part.getSubParts())
            n += countNodes(sub);
        return n;
    }

    @Override
    public Component display() {
        Component text = (new InlineTrickData(spell)).asText(true);
        return text.copy().withStyle(text.getStyle().applyTo(Style.EMPTY.withColor(ChatFormatting.WHITE)));
    }

    // ---- serialization ----------------------------------------------------

    /**
     * Base64 of the gzipped protocol-versioned fragment bytes — exactly
     * {@code Fragment.toBase64()} / {@code Fragment.fromBase64(String)}.
     */
    public static final Codec<SpellPart> SPELL_CODEC = Codec.STRING.flatXmap(
            s -> {
                try {
                    Fragment f = Fragment.fromBase64(s);
                    return f instanceof SpellPart sp
                            ? DataResult.success(sp)
                            : DataResult.error(() -> "decoded fragment is not a spell part");
                } catch (Exception e) {
                    return DataResult.error(() -> "invalid trick spell data: " + e.getMessage());
                }
            },
            sp -> DataResult.success(sp.toBase64()));

    private static TrickIota fromBytes(byte[] bytes) {
        try {
            Fragment f = Fragment.fromBytes(bytes);
            return f instanceof SpellPart sp ? new TrickIota(sp) : new TrickIota(new SpellPart());
        } catch (Exception e) {
            // Degrade to an empty spell instead of killing the packet.
            return new TrickIota(new SpellPart());
        }
    }

    public static final IotaType<TrickIota> TYPE = new IotaType<>() {
        public static final MapCodec<TrickIota> CODEC = SPELL_CODEC
                .xmap(TrickIota::new, TrickIota::getSpell)
                .fieldOf("value");
        public static final StreamCodec<RegistryFriendlyByteBuf, TrickIota> STREAM_CODEC =
                ByteBufCodecs.BYTE_ARRAY
                        .map(TrickIota::fromBytes, iota -> iota.getSpell().toBytes())
                        .mapStream(buffer -> buffer);

        @Override
        public MapCodec<TrickIota> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, TrickIota> streamCodec() {
            return STREAM_CODEC;
        }

        @Override
        public int color() {
            return 0xff_ffd700;
        }

        @Override
        public boolean usesListCommas() {
            return false;
        }
    };
}
