package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import at.petrak.hexcasting.api.casting.eval.CastResult;
import at.petrak.hexcasting.api.casting.eval.ResolvedPatternType;
import at.petrak.hexcasting.api.casting.eval.vm.*;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.Vec3Iota;
import at.petrak.hexcasting.api.casting.mishaps.*;
import at.petrak.hexcasting.api.casting.eval.sideeffects.OperatorSideEffect;
import at.petrak.hexcasting.api.utils.TreeList;
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds;
import java.util.List;

/** Fold pure quoted vectors, preserving each callback and allocating immutable images. */
public final class FastQuotedVectors {
    private FastQuotedVectors() {}

    public static CastResult execute(CastingVM vm, TreeList<Iota> source, SpellContinuation parent,
                                     boolean metacasting, ExecutionScope scope) {
        int count = 0;
        int bound = Math.min(source.size(), 1024);
        while (count < bound && source.get(count).getClass() == Vec3Iota.class) count++;
        var sound = metacasting ? scope.loopTickHermesSound() : HexEvalSounds.NORMAL_EXECUTE.get();
        for (int index = 0; index < count; index++) {
            CastingImage current = vm.getImage();
            if (!scope.canUseStaffTickCallback(vm.getEnv()) || current.getParenCount() <= 0
                    || current.getEscapeNext() || current.getSimulateNext()
                    || !scope.hasStableValidatedStack(current.getStack())) {
                scope.startStep();
                return new FrameEvaluate(source.drop(index), metacasting).evaluate(parent, vm.getEnv().getWorld(), vm);
            }
            scope.startStep();
            Iota vector = source.get(index);
            if (index < count - 1 && scope.canCollapseQuotedCallbacks(vm.getEnv(), sound)) {
                if (current.getOpsConsumed() > scope.actualMaxOpCount(vm.getEnv()))
                    return new CastResult(vector, after(source, index + 1, parent, metacasting), null,
                            List.of(new OperatorSideEffect.DoMishap(new MishapEvalTooMuch(), new Mishap.Context(null, null))),
                            ResolvedPatternType.ERRORED, HexEvalSounds.MISHAP.get());
                try {
                    var quoted = current.getParenthesized();
                    for (int j = index; j < count; j++) quoted = scope.appendQuotedVector(quoted, source.get(j));
                    CastingImage output = new CastingImage(current.getStack(), current.getParenCount(), quoted,
                            current.getEscapeNext(), current.getSimulateNext(), current.getOpsConsumed(), current.getUserData());
                    scope.recordCollapsedQuotes(vm.getEnv(), sound, count - index - 1);
                    return new CastResult(source.get(count - 1), after(source, count, parent, metacasting), output,
                            List.of(), ResolvedPatternType.ESCAPED, sound);
                } catch (Exception exception) { return internalFailure(vector, after(source, index + 1, parent, metacasting), exception); }
            }
            CastingImage image;
            try { image = scope.quoteVector(current, vector); }
            catch (Exception exception) { return internalFailure(vector, after(source, index + 1, parent, metacasting), exception); }
            SpellContinuation next = index == count - 1 ? after(source, index + 1, parent, metacasting) : parent;
            if (image.getOpsConsumed() > scope.actualMaxOpCount(vm.getEnv()))
                return new CastResult(vector, after(source, index + 1, parent, metacasting), null,
                        List.of(new OperatorSideEffect.DoMishap(new MishapEvalTooMuch(), new Mishap.Context(null, null))),
                        ResolvedPatternType.ERRORED, HexEvalSounds.MISHAP.get());
            if (index == count - 1)
                return new CastResult(vector, next, image, List.of(), ResolvedPatternType.ESCAPED, sound);
            vm.setImage(image);
            scope.postQuotedVector(vm.getEnv(), sound);
        }
        throw new IllegalStateException("Quoted vector run must be nonempty");
    }

    public static CastResult single(CastingVM vm, Iota vector, SpellContinuation next,
                                    boolean metacasting, ExecutionScope scope) {
        try {
            return new CastResult(vector, next, scope.quoteVector(vm.getImage(), vector), List.of(),
                    ResolvedPatternType.ESCAPED, metacasting ? scope.loopTickHermesSound() : HexEvalSounds.NORMAL_EXECUTE.get());
        } catch (Exception exception) { return internalFailure(vector, next, exception); }
    }

    private static CastResult internalFailure(Iota vector, SpellContinuation next, Exception exception) {
        exception.printStackTrace();
        return new CastResult(vector, next, null,
                List.of(new OperatorSideEffect.DoMishap(new MishapInternalException(exception), new Mishap.Context(null, null))),
                ResolvedPatternType.ERRORED, HexEvalSounds.MISHAP.get());
    }

    private static SpellContinuation after(TreeList<Iota> source, int consumed, SpellContinuation parent, boolean meta) {
        return consumed == source.size() ? parent : parent.pushFrame(new FrameEvaluate(source.drop(consumed), meta));
    }
}
