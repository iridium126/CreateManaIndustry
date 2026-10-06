package com.iridium126.createmanaindustry.mixin.hexjit;

import at.petrak.hexcasting.api.casting.eval.CastResult;
import at.petrak.hexcasting.api.casting.eval.ResolvedPatternType;
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM;
import at.petrak.hexcasting.api.casting.eval.vm.FrameEvaluate;
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.ListIota;
import at.petrak.hexcasting.api.casting.iota.PatternIota;
import at.petrak.hexcasting.api.utils.TreeList;
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.ExecutionScope;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.FastLoopTickDispatch;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.HexJitRuntime;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.JitCompatibility;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.PatternIotaLoopDispatchAccess;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** Specializes repeated metacast frames while preserving Hexcasting's per-iota evaluation path. */
@Mixin(value = FrameEvaluate.class, remap = false)
public abstract class FrameEvaluateMixin {
    @Shadow @Final private TreeList<Iota> list;
    @Shadow @Final private boolean isMetacasting;
    @Unique private TreeList<Iota> cmi$cachedTail;

    /**
     * Mirrors Hexcasting's FrameEvaluate.evaluate, sharing only immutable loop suffixes and frames.
     * The harness still evaluates one iota at a time, so op limits, observers, and effects retain
     * their original order and count.
     */
    @Overwrite
    public CastResult evaluate(SpellContinuation continuation, ServerLevel level, CastingVM harness) {
        if (list.isEmpty()) {
            return new CastResult(new ListIota(list), continuation, null, java.util.List.of(),
                    ResolvedPatternType.EVALUATED, HexEvalSounds.HERMES.get());
        }

        boolean autoServerCast = ServerConfig.hexJitMode == ServerConfig.HexJitMode.AUTO
                && HexJitRuntime.onServerThread();
        boolean needsScope = autoServerCast && (ServerConfig.hexJitCoalesceEvalSounds
                || isMetacasting && ServerConfig.hexJitLoopSpecialization);
        ExecutionScope activeScope = needsScope ? ExecutionScope.current() : null;
        boolean canSpecializeLoop = isMetacasting && activeScope != null
                && activeScope.loopSpecializationEnabled();
        boolean canCoalesceSounds = activeScope != null && activeScope.coalesceEvalSoundsEnabled();
        ExecutionScope scope = canSpecializeLoop ? activeScope : null;
        ExecutionScope loopDispatchScope = scope != null ? scope
                : autoServerCast && ServerConfig.hexJitLoopSpecialization
                ? ExecutionScope.current() : null;
        if (scope == null && ServerConfig.hexJitReuseFrameTail
                && ServerConfig.hexJitMode == ServerConfig.HexJitMode.AUTO
                && HexJitRuntime.onServerThread() && JitCompatibility.frameTailCacheReady()) {
            if (cmi$cachedTail == null) cmi$cachedTail = list.tail();
        }

        SpellContinuation next = continuation;
        if (scope != null) {
            next = scope.cachedFrameLoopContinuation(list, continuation);
        } else {
            TreeList<Iota> tail = cmi$cachedTail == null ? list.tail() : cmi$cachedTail;
            if (!tail.isEmpty()) next = continuation.pushFrame(new FrameEvaluate(tail, isMetacasting));
        }

        boolean wasMetacastingFrame = activeScope != null && activeScope.inMetacastingFrame();
        if (activeScope != null) activeScope.metacastingFrame(isMetacasting);
        CastResult update;
        try {
            Iota head = list.head();
            if (ServerConfig.hexJitLoopTickDispatch && loopDispatchScope != null
                    && loopDispatchScope.loopSpecializationEnabled()
                    && loopDispatchScope.fastTickActionEnabled()
                    && !harness.getImage().getEscapeNext() && !harness.getImage().getSimulateNext()
                    && harness.getImage().getParenCount() == 0
                    && head instanceof PatternIota pattern
                    && pattern instanceof PatternIotaLoopDispatchAccess lookup) {
                var match = lookup.cmi$getCachedLoopTickMatch(loopDispatchScope.registryGeneration());
                if (match != null && (!lookup.cmi$cachedLoopTickRequiresEnlightenment()
                        || harness.getEnv().isEnlightened())) {
                    update = loopDispatchScope.loopTickBatchEnabled()
                            ? FastLoopTickDispatch.executeRun(harness, next, pattern, match, loopDispatchScope)
                            : FastLoopTickDispatch.execute(harness, next, pattern, match, loopDispatchScope);
                } else {
                    loopDispatchScope.invalidateLoopTickParticleColor();
                    update = harness.executeInner(head, level, next);
                }
            } else {
                if (loopDispatchScope != null) loopDispatchScope.invalidateLoopTickParticleColor();
                update = harness.executeInner(head, level, next);
            }
        } finally {
            if (activeScope != null) activeScope.metacastingFrame(wasMetacastingFrame);
        }
        if (isMetacasting) {
            if (activeScope != null && activeScope.loopSpecializationEnabled()) {
                if (update.getSound() == activeScope.loopTickHermesSound()) {
                    if (canCoalesceSounds && activeScope.canSkipEmptyCallbacks(harness.getEnv()))
                        activeScope.recordEvalSoundCopySkipped();
                    return update;
                }
                if (update.getSound() == activeScope.loopTickMishapSound()) return update;
            }
            var mishapSound = activeScope != null && activeScope.loopSpecializationEnabled()
                    ? activeScope.loopTickMishapSound() : HexEvalSounds.MISHAP.get();
            if (!update.getSound().equals(mishapSound)) {
                if (canCoalesceSounds && activeScope != null
                        && activeScope.canSkipEmptyCallbacks(harness.getEnv())) {
                    activeScope.recordEvalSoundCopySkipped();
                    return update;
                }
                var hermesSound = activeScope != null && activeScope.loopSpecializationEnabled()
                        ? activeScope.loopTickHermesSound() : HexEvalSounds.HERMES.get();
                if (update.getSound().equals(hermesSound)) return update;
                return new CastResult(update.getCast(), update.getContinuation(), update.getNewData(),
                        update.getSideEffects(), update.getResolutionType(), hermesSound);
            }
        }
        return update;
    }
}
