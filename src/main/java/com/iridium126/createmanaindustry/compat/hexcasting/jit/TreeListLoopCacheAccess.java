package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import at.petrak.hexcasting.api.casting.eval.vm.FrameEvaluate;
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation;
import at.petrak.hexcasting.api.utils.TreeList;

/** Mutable loop-frame cache attached to an immutable Hexcasting TreeList. */
public interface TreeListLoopCacheAccess {
    FrameEvaluate cmi$getLoopSuccessor();
    void cmi$setLoopSuccessor(FrameEvaluate successor);
    ExecutionScope cmi$getLoopScope();
    SpellContinuation cmi$getLoopParent();
    SpellContinuation cmi$getLoopContinuation();
    void cmi$setLoopContinuation(ExecutionScope scope, SpellContinuation parent, SpellContinuation continuation);
    void cmi$clearLoopContinuation(ExecutionScope scope);
}
