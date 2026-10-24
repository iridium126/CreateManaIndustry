package com.iridium126.createmanaindustry.mixin.hextrick;

import at.petrak.hexcasting.api.casting.eval.*;
import at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv;
import at.petrak.hexcasting.api.casting.eval.vm.*;
import at.petrak.hexcasting.api.casting.iota.Iota;
import com.iridium126.createmanaindustry.compat.hexcasting.HexCastChainState;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.ExecutionScope;
import com.iridium126.createmanaindustry.compat.trickster.casting.*;
import com.iridium126.createmanaindustry.compat.trickster.sharedfate.SharedFateMishaps;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.ArrayDeque;
import java.util.List;
import net.minecraft.server.level.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = CastingVM.class, remap = false, priority = 1100)
public abstract class HexCastingChainMixin {
    @Unique private final ArrayDeque<HexCastChainState.Invocation> cmi$chainCalls = new ArrayDeque<>();

    @WrapMethod(method = "queueExecuteAndWrapIotas")
    private ExecutionClientView cmi$chain(List<Iota> iotas, ServerLevel world, Operation<ExecutionClientView> original) {
        CastingVM vm = (CastingVM)(Object)this;
        if (!(vm.getEnv().getCastingEntity() instanceof ServerPlayer player)) {
            if (cmi$chainCalls.isEmpty()) return original.call(iotas, world);
            cmi$chainCalls.push(new HexCastChainState.Invocation(null));
            try { return original.call(iotas, world); } finally { cmi$chainCalls.pop(); }
        }
        boolean inherited = CastChains.belongsTo(player.getUUID());
        CastChain chain = inherited ? CastChains.current() : CastChains.attach(HexCastChainState.get(vm.getImage()), player, this);
        var invocation = new HexCastChainState.Invocation(chain);
        cmi$chainCalls.push(invocation);
        if (!inherited && vm.getEnv() instanceof StaffCastEnv) chain.retain(chain);
        boolean completed = false;
        try (var ignored = CastChains.enter(chain); var tracked = SharedFateMishaps.enterChain(vm, chain)) {
            ExecutionClientView view = original.call(iotas, world);
            invocation.terminal = view.isStackClear() || !view.getResolutionType().getSuccess();
            vm.setImage(HexCastChainState.publish(vm.getImage(), invocation.terminal ? null : chain.snapshot()));
            completed = true;
            return view;
        } finally {
            cmi$chainCalls.pop();
            if (!inherited) {
                try {
                    if (!completed || invocation.terminal) {
                        vm.setImage(HexCastChainState.publish(vm.getImage(), null));
                        HexCastChainState.clearSaved(player, chain);
                        CastChains.release(chain, chain);
                    }
                } finally { CastChains.release(chain, this); }
            }
        }
    }

    @WrapOperation(method = "queueExecuteAndWrapIotas", at = @At(value = "INVOKE", target =
            "Lat/petrak/hexcasting/api/casting/eval/CastingEnvironment;postCast(Lat/petrak/hexcasting/api/casting/eval/vm/CastingImage;)V"))
    private void cmi$publishBeforePostCast(CastingEnvironment env, CastingImage image, Operation<Void> original,
            @Local ResolvedPatternType lastResolutionType, @Local(name = "isStackClear") boolean stackClear) {
        var invocation = cmi$chainCalls.peek();
        if (invocation == null || invocation.chain == null) { original.call(env, image); return; }
        ExecutionScope.flushBeforePostCast();
        invocation.terminal = stackClear || !lastResolutionType.getSuccess();
        CastingVM vm = (CastingVM)(Object)this;
        var published = HexCastChainState.publish(vm.getImage(), invocation.terminal ? null : invocation.chain.snapshot());
        vm.setImage(published);
        original.call(env, published);
    }
}
