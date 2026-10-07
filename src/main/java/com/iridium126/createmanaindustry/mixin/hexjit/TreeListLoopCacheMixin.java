package com.iridium126.createmanaindustry.mixin.hexjit;

import at.petrak.hexcasting.api.casting.eval.vm.FrameEvaluate;
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation;
import at.petrak.hexcasting.api.utils.TreeList;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.ExecutionScope;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.TreeListLoopCacheAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = TreeList.class, remap = false)
public abstract class TreeListLoopCacheMixin implements TreeListLoopCacheAccess {
    @Unique private FrameEvaluate cmi$loopSuccessor;
    @Unique private ExecutionScope cmi$loopScope;
    @Unique private SpellContinuation cmi$loopParent;
    @Unique private SpellContinuation cmi$loopContinuation;

    @Override public FrameEvaluate cmi$getLoopSuccessor() { return cmi$loopSuccessor; }
    @Override public void cmi$setLoopSuccessor(FrameEvaluate successor) { cmi$loopSuccessor = successor; }
    @Override public ExecutionScope cmi$getLoopScope() { return cmi$loopScope; }
    @Override public SpellContinuation cmi$getLoopParent() { return cmi$loopParent; }
    @Override public SpellContinuation cmi$getLoopContinuation() { return cmi$loopContinuation; }
    @Override public void cmi$setLoopContinuation(ExecutionScope scope, SpellContinuation parent,
                                                   SpellContinuation continuation) {
        cmi$loopScope = scope;
        cmi$loopParent = parent;
        cmi$loopContinuation = continuation;
    }
    @Override public void cmi$clearLoopContinuation(ExecutionScope scope) {
        if (cmi$loopScope != scope) return;
        cmi$loopScope = null;
        cmi$loopParent = null;
        cmi$loopContinuation = null;
    }
}
