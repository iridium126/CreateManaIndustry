package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import at.petrak.hexcasting.api.casting.ParticleSpray;
import at.petrak.hexcasting.api.casting.castables.Action;
import at.petrak.hexcasting.api.casting.castables.SpellAction;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.CastResult;
import at.petrak.hexcasting.api.casting.eval.ResolvedPatternType;
import at.petrak.hexcasting.api.casting.eval.sideeffects.OperatorSideEffect;
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation;
import at.petrak.hexcasting.api.casting.iota.PatternIota;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughArgs;
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughMedia;
import at.petrak.hexcasting.common.lib.hex.HexActions;
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds;
import java.util.ArrayList;
import java.util.List;

/**
 * The one deliberately narrow action specialization: use a compact fixed-size list for the
 * two arguments consumed by the stock Add Motion SpellAction. It preserves the upstream action
 * work while returning the VM's CastResult directly, avoiding an immediately-unwrapped
 * intermediate OperationResult.
 */
public final class FastAddMotionArguments {
    private static final SpellAction STOCK_ADD_MOTION = (SpellAction) HexActions.ADD_MOTION.value().action();
    private FastAddMotionArguments() {}

    public static boolean supports(Action action) {
        return action == STOCK_ADD_MOTION && JitCompatibility.fastAddMotionReady();
    }

    public static CastResult operate(CastingEnvironment env, CastingImage image,
                                     SpellContinuation continuation, PatternIota cast) {
        SpellAction spell = STOCK_ADD_MOTION;
        var stack = image.getStack();
        int argc = spell.getArgc();
        if (argc > stack.size()) {
            throw new MishapNotEnoughArgs(argc, stack.size());
        }

        // SpellAction.operate uses takeRight(argc); the stock Add Motion action only reads
        // indices 0 and 1, so a compact pair preserves both ordering and values without building
        // a second TreeList slice on every hot-loop iteration. Keep the upstream path for any
        // transformed/nonstandard argc value, after reading it exactly once as the original does.
        List<Iota> args = argc == 2
                ? List.of(stack.get(stack.size() - 2), stack.get(stack.size() - 1))
                : stack.takeRight(argc);
        // For the stock two-argument action, repeated persistent-list init is the exact prefix
        // produced by dropRight(2), and avoids rebuilding that prefix through TreeList.slice.
        var stackWithoutArgs = argc == 2
                ? stack.init().init()
                : stack.dropRight(argc);
        var userData = FastCompoundTagCopy.copy(image.getUserData());
        SpellAction.Result result = spell.executeWithUserdata(args, env, userData);

        List<ParticleSpray> particles = result.getParticles();
        int particleCount = particles.size();
        List<OperatorSideEffect> sideEffects = new ArrayList<>(particleCount + 2);
        if (env.extractMedia(result.getCost(), true) > 0) {
            throw new MishapNotEnoughMedia(result.getCost());
        }
        if (result.getCost() > 0) {
            sideEffects.add(new OperatorSideEffect.ConsumeMedia(result.getCost()));
        }
        sideEffects.add(new OperatorSideEffect.AttemptSpell(result.getEffect(),
                spell.hasCastingSound(env), spell.awardsCastingStat(env)));
        // Stock Add Motion returns a singleton Kotlin list. Indexed access preserves the exact
        // order while avoiding an iterator allocation on every hot-loop invocation.
        for (int index = 0; index < particleCount; index++) {
            sideEffects.add(new OperatorSideEffect.Particles(particles.get(index)));
        }

        CastingImage image2 = image.copy(stackWithoutArgs, image.getParenCount(), image.getParenthesized(),
                image.getEscapeNext(), image.getSimulateNext(), image.getOpsConsumed() + result.getOpCount(), userData);
        var sound = spell.hasCastingSound(env) ? HexEvalSounds.SPELL.get() : HexEvalSounds.MUTE.get();
        return new CastResult(cast, continuation, image2, sideEffects, ResolvedPatternType.EVALUATED, sound);
    }
}
