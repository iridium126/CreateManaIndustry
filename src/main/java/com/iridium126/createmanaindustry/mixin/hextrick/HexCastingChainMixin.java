package com.iridium126.createmanaindustry.mixin.hextrick;

import at.petrak.hexcasting.api.casting.eval.*;
import at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv;
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM;
import at.petrak.hexcasting.api.casting.iota.Iota;
import com.iridium126.createmanaindustry.compat.trickster.*;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = CastingVM.class, remap = false, priority = 1100)
public abstract class HexCastingChainMixin {
    @WrapMethod(method = "queueExecuteAndWrapIotas")
    private ExecutionClientView cmi$chain(List<Iota> iotas, ServerLevel world, Operation<ExecutionClientView> original) {
        CastingVM vm = (CastingVM)(Object)this;
        if (!(vm.getEnv().getCastingEntity() instanceof ServerPlayer player)) return original.call(iotas, world);
        if (CastChains.belongsTo(player.getUUID())) return original.call(iotas, world);
        CastChain chain = CastChain.decode(vm.getImage().getUserData().getString("cmi_cast_chain"));
        chain = CastChains.attach(chain, player, this);
        // A manually drawn staff session survives between VM instances/patterns.
        boolean staff = vm.getEnv() instanceof StaffCastEnv;
        if (staff) chain.retain(chain);
        boolean failed = true;
        try (var ignored = CastChains.enter(chain)) {
            ExecutionClientView view = original.call(iotas, world);
            failed = !view.getResolutionType().getSuccess();
            vm.getImage().getUserData().putString("cmi_cast_chain", chain.encode());
            if (view.isStackClear() || failed) {
                vm.getImage().getUserData().remove("cmi_cast_chain");
                CastChains.release(chain, chain);
            }
            return view;
        } finally {
            if (failed) CastChains.release(chain, chain);
            CastChains.release(chain, this);
        }
    }
}
