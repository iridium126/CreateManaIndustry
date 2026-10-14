package com.iridium126.createmanaindustry.mixin.hextrick;

import com.iridium126.createmanaindustry.compat.trickster.casting.CastChain;
import com.iridium126.createmanaindustry.compat.trickster.casting.CastChains;
import com.iridium126.createmanaindustry.compat.trickster.sharedfate.SharedFateMishaps;

import at.petrak.hexcasting.api.casting.eval.*;
import at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv;
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM;
import at.petrak.hexcasting.api.casting.iota.Iota;
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
        if (CastChains.belongsTo(player.getUUID())) {
            try(var tracked=SharedFateMishaps.enterChain(vm,CastChains.current())) {return original.call(iotas,world);}
        }
        CastChain chain = CastChain.decode(vm.getImage().getUserData().getString("cmi_cast_chain"));
        var savedTargets = vm.getImage().getUserData().getList("cmi_shared_fate_targets", net.minecraft.nbt.Tag.TAG_STRING);
        chain.restoreFateTargets(java.util.stream.IntStream.range(0, savedTargets.size()).mapToObj(savedTargets::getString).toList());
        chain = CastChains.attach(chain, player, this);
        // A manually drawn staff session survives between VM instances/patterns.
        boolean staff = vm.getEnv() instanceof StaffCastEnv;
        if (staff) chain.retain(chain);
        boolean failed = true;
        try (var ignored = CastChains.enter(chain); var tracked=SharedFateMishaps.enterChain(vm,chain)) {
            ExecutionClientView view = original.call(iotas, world);
            failed = !view.getResolutionType().getSuccess();
            vm.getImage().getUserData().putString("cmi_cast_chain", chain.encode());
            var targets = new net.minecraft.nbt.ListTag();
            chain.fateTargets().forEach(id -> targets.add(net.minecraft.nbt.StringTag.valueOf(id.toString())));
            vm.getImage().getUserData().put("cmi_shared_fate_targets", targets);
            if (view.isStackClear() || failed) {
                vm.getImage().getUserData().remove("cmi_cast_chain");
                vm.getImage().getUserData().remove("cmi_shared_fate_targets");
                CastChains.release(chain, chain);
            }
            return view;
        } finally {
            if (failed) CastChains.release(chain, chain);
            CastChains.release(chain, this);
        }
    }
}
