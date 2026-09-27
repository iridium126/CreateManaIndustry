package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import at.petrak.hexcasting.api.casting.eval.CastResult;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.ResolvedPatternType;
import at.petrak.hexcasting.api.casting.eval.sideeffects.OperatorSideEffect;
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation;
import at.petrak.hexcasting.api.casting.iota.DoubleIota;
import at.petrak.hexcasting.api.casting.iota.PatternIota;
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughMedia;
import at.petrak.hexcasting.common.casting.actions.math.SpecialHandlerNumberLiteral;
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds;
import java.util.ArrayList;

/** Exact fast path for Hexcasting's verified zero-argument numeric special action. */
public final class FastNumberLiteral {
    private FastNumberLiteral() {}

    public static boolean supports(Object handler) {
        return JitCompatibility.fastNumberLiteralReady()
                && handler != null && handler.getClass() == SpecialHandlerNumberLiteral.class;
    }

    public static CastResult execute(CastingEnvironment environment, CastingImage image,
                                     SpellContinuation continuation, PatternIota cast,
                                     SpecialHandlerNumberLiteral handler) {
        var stack = image.getStack().appended(new DoubleIota(handler.getX()));
        if (environment.extractMedia(0L, true) > 0) throw new MishapNotEnoughMedia(0L);

        // ConstMediaAction.operate always emits ConsumeMedia, even when its default cost is zero.
        var sideEffects = new ArrayList<OperatorSideEffect>(1);
        sideEffects.add(new OperatorSideEffect.ConsumeMedia(0L));
        CastingImage nextImage = image.copy(stack, image.getParenCount(), image.getParenthesized(),
                image.getEscapeNext(), image.getSimulateNext(), image.getOpsConsumed() + 1, image.getUserData());
        return new CastResult(cast, continuation, nextImage, sideEffects,
                ResolvedPatternType.EVALUATED, HexEvalSounds.NORMAL_EXECUTE.get());
    }
}
