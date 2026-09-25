package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import at.petrak.hexcasting.api.casting.arithmetic.engine.HashCons;
import at.petrak.hexcasting.api.casting.arithmetic.engine.NoOperatorCandidatesException;
import at.petrak.hexcasting.api.casting.arithmetic.operator.Operator;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.OperationResult;
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;

/** One pattern in one engine. Never retains arguments, images, environments or continuations. */
public final class ArithmeticSite {
    /** One structural bytecode call site is shared by every immutable Operator receiver. */
    public static final long CALL_SITE = HexJitRuntime.nextSite();
    public static final CallCompiler.Description CALL = new CallCompiler.Description(
            "at/petrak/hexcasting/api/casting/arithmetic/operator/Operator", "operate",
            "(Lat/petrak/hexcasting/api/casting/eval/CastingEnvironment;"
                    + "Lat/petrak/hexcasting/api/casting/eval/vm/CastingImage;"
                    + "Lat/petrak/hexcasting/api/casting/eval/vm/SpellContinuation;)"
                    + "Lat/petrak/hexcasting/api/casting/eval/OperationResult;", false);
    private final ArithmeticCandidates candidates;
    private final long generation;
    private HashCons lastKey;
    private IotaType<?> top, second, third;

    public ArithmeticSite(ArithmeticCandidates candidates, long generation) {
        this.candidates = candidates;
        this.generation = generation;
    }

    public boolean valid(ArithmeticCandidates current, long epoch) {
        return candidates == current && generation == epoch;
    }

    /** Caller checks arity and underflow before entry; neither fallback nor this method replays an operation. */
    public OperationResult execute(HexPattern pattern, Map<HashCons, Operator> originalCache,
                                   CastingEnvironment env, CastingImage image, SpellContinuation continuation,
                                   CompiledCall code)
            throws Throwable {
        var stack = image.getStack();
        int arity = candidates.cmi$arity();
        int size = stack.size();
        // Preserve upstream getType order (top to bottom), including on a cache miss.
        IotaType<?> a = arity > 0 ? stack.get(size - 1).getType() : null;
        IotaType<?> b = arity > 1 ? stack.get(size - 2).getType() : null;
        IotaType<?> c = arity > 2 ? stack.get(size - 3).getType() : null;
        Operator operator = null;
        if (arity <= 3 && lastKey != null && a == top && b == second && c == third) {
            // The upstream map remains authoritative, even if another caller has cleared/replaced an entry.
            operator = originalCache.get(lastKey);
        }
        if (operator == null) {
            HashCons key = new HashCons.Pattern(pattern);
            var args = new ArrayList<Iota>(arity);
            for (int i = 0; i < arity; i++) {
                Iota value = stack.get(size - 1 - i);
                IotaType<?> type = switch (i) { case 0 -> a; case 1 -> b; case 2 -> c; default -> value.getType(); };
                key = new HashCons.Pair(type, key);
                args.add(value);
            }
            Collections.reverse(args);
            operator = originalCache.computeIfAbsent(key, ignored -> {
                for (Operator candidate : candidates.cmi$operators())
                    if (candidate.accepts.test(args)) return candidate;
                throw new NoOperatorCandidatesException(candidates.cmi$pattern(), args,
                        "No implementation candidates for op " + candidates.cmi$pattern() + " on args: " + args);
            });
            if (arity <= 3) { lastKey = key; top = a; second = b; third = c; }
        }
        if (code == null) return operator.operate(env, image, continuation);
        ExecutionScope.markCompiled();
        // Deliberately outside compiler error handling: an action exception must never cause a retry.
        return (OperationResult) code.call(operator, env, image, continuation, null);
    }
}
