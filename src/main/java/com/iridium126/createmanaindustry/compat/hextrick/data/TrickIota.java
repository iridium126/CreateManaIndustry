package com.iridium126.createmanaindustry.compat.hextrick.data;

import at.petrak.hexcasting.api.casting.eval.CastResult;
import at.petrak.hexcasting.api.casting.eval.ResolvedPatternType;
import at.petrak.hexcasting.api.casting.eval.sideeffects.OperatorSideEffect;
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM;
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import at.petrak.hexcasting.api.casting.iota.ListIota;
import at.petrak.hexcasting.api.casting.mishaps.Mishap;
import at.petrak.hexcasting.api.casting.mishaps.MishapEvalTooMuch;
import at.petrak.hexcasting.api.utils.TreeList;
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds;
import com.iridium126.createmanaindustry.compat.hextrick.bootstrap.CMIHexIotaTypes;
import com.iridium126.createmanaindustry.compat.hextrick.execution.BridgeExecutionContext;
import com.iridium126.createmanaindustry.compat.hextrick.execution.SpellExecutionBridge;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import dev.enjarai.trickster.spell.Fragment;
import dev.enjarai.trickster.spell.SpellPart;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
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
 * spell round-trips through the bounded adapter for the supported Endec protocol.
 * <p>
 * Displayed inline via {@link InlineTrickData}, rendered with Trickster's own
 * {@code CircleRenderer} so the glyph stack looks exactly like a Trickster
 * spell fragment tooltip.
 */
public class TrickIota extends Iota {

    private final TrickSpellSnapshot snapshot;

    public TrickIota(SpellPart spell) {
        super(() -> CMIHexIotaTypes.TRICK.get());
        this.snapshot = new TrickSpellSnapshot(spell);
    }

    public SpellPart getSpell() {
        return snapshot.copySpell();
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
        var context = BridgeExecutionContext.current();
        if (context != null && !context.beginHexBridgeInstruction()) {
            return new CastResult(this, SpellContinuation.Done.INSTANCE, null,
                    List.of(new OperatorSideEffect.DoMishap(
                            new MishapEvalTooMuch(),
                            new Mishap.Context(null, null))),
                    ResolvedPatternType.ERRORED, HexEvalSounds.MISHAP.get());
        }
        CastingImage image = vm.getImage();
        TreeList<Iota> stack = image.getStack();
        List<Iota> params = new ArrayList<>();
        if (!stack.isEmpty() && stack.last() instanceof ListIota list) {
            params.addAll(list.getList());
            stack = stack.init();
        }

        SpellExecutionBridge.SpellExecutionResult execution = SpellExecutionBridge.tryExecute(getSpell(), params, vm.getEnv());
        if (execution.status() == SpellExecutionBridge.SpellExecutionStatus.COMPLETED) {
            if (execution.result() != null) {
                stack = stack.appended(execution.result());
            }
        }

        CastingImage nextImage = image.copy(stack, image.getParenCount(), image.getParenthesized(),
                image.getEscapeNext(), image.getSimulateNext(), image.getOpsConsumed() + 1, image.getComponents());
        return new CastResult(this, continuation, nextImage, List.of(),
                ResolvedPatternType.EVALUATED, HexEvalSounds.NORMAL_EXECUTE.get());
    }

    @Override
    public boolean isTruthy(at.petrak.hexcasting.api.casting.eval.CastingEnvironment env) {
        return !snapshot.isEmpty();
    }

    @Override
    protected boolean toleratesOther(Iota that) {
        return typesMatch(this, that)
                && that instanceof TrickIota other
                && this.snapshot.equals(other.snapshot);
    }

    @Override
    public int hashCode() {
        return snapshot.hashCode();
    }

    /**
     * Size of the full fragment graph, so {@code IotaType.TYPED_CODEC}'s
     * serialisation size guard degrades oversized spells to GarbageIota on the
     * NBT/codec path instead of failing.
     */
    @Override
    public int size() {
        return snapshot.size();
    }

    @Override
    public int depth() {
        return snapshot.depth();
    }

    @Override
    public Iterable<Iota> subIotas() {
        return snapshot.embeddedIotas();
    }

    @Override
    protected Iota visitChildren(UnaryOperator<Iota> visitor) {
        SpellPart spell = getSpell();
        Fragment changed = FragmentStructures.visit(spell, visitor);
        return changed == spell ? this : new TrickIota((SpellPart) changed);
    }

    @Override
    public Component display() {
        Component text = (new InlineTrickData(getSpell())).asText(true);
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
                    Fragment f = BridgeFragmentCodec.fromBase64(s);
                    return f instanceof SpellPart sp
                            ? DataResult.success(sp)
                            : DataResult.error(() -> "decoded fragment is not a spell part");
                } catch (Exception e) {
                    return DataResult.error(() -> "invalid trick spell data: " + e.getMessage());
                }
            },
            sp -> {
                try { return DataResult.success(BridgeFragmentCodec.toBase64(sp)); }
                catch (RuntimeException failure) { return DataResult.error(() -> "invalid trick spell: " + failure.getMessage()); }
            });

    private static TrickIota fromBytes(byte[] bytes) {
        try {
            return new TrickIota(BridgeFragmentCodec.decodeSpell(bytes));
        } catch (Exception e) {
            throw new io.netty.handler.codec.DecoderException("Invalid TrickIota", e);
        }
    }

    private static DataResult<TrickIota> fromSpell(SpellPart spell) {
        try {
            return DataResult.success(new TrickIota(spell));
        } catch (RuntimeException failure) {
            return DataResult.error(() -> "invalid trick spell snapshot: " + failure.getMessage());
        }
    }

    public static final IotaType<TrickIota> TYPE = new IotaType<>() {
        public static final MapCodec<TrickIota> CODEC = SPELL_CODEC
                .comapFlatMap(TrickIota::fromSpell, TrickIota::getSpell)
                .fieldOf("value");
        public static final StreamCodec<RegistryFriendlyByteBuf, TrickIota> STREAM_CODEC =
                ByteBufCodecs.byteArray(DecodeBudget.MAX_ENCODED)
                        .map(TrickIota::fromBytes, iota -> iota.snapshot.copyBytes())
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
