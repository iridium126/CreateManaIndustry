package com.iridium126.createmanaindustry.mixin.hextrick;

import at.petrak.hexcasting.api.casting.eval.*;
import at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv;
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM;
import at.petrak.hexcasting.api.casting.iota.Iota;
import com.iridium126.createmanaindustry.compat.trickster.casting.CastChain;
import com.iridium126.createmanaindustry.compat.trickster.casting.CastChains;
import com.iridium126.createmanaindustry.compat.trickster.partition.SavedPartition;
import com.iridium126.createmanaindustry.compat.trickster.sharedfate.SharedFateMishaps;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.List;
import java.util.stream.IntStream;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
        var savedTargets = vm.getImage().getUserData().getList("cmi_shared_fate_targets", Tag.TAG_STRING);
        chain.restoreFateTargets(IntStream.range(0, savedTargets.size()).mapToObj(savedTargets::getString).toList());
        String origins = vm.getImage().getUserData().getString("cmi_effect_origins");
        if (!origins.isEmpty()) chain.restoreOrigins(origins);
        String partition=vm.getImage().getUserData().getString("cmi_partition");
        if(!partition.isEmpty())chain.partition(SavedPartition.decode(partition));
        chain = CastChains.attach(chain, player, this);
        // A manually drawn staff session survives between VM instances/patterns.
        boolean staff = vm.getEnv() instanceof StaffCastEnv;
        if (staff) chain.retain(chain);
        boolean failed = true;
        try (var ignored = CastChains.enter(chain); var tracked=SharedFateMishaps.enterChain(vm,chain)) {
            ExecutionClientView view = original.call(iotas, world);
            failed = !view.getResolutionType().getSuccess();
            vm.getImage().getUserData().putString("cmi_cast_chain", chain.encode());
            var targets = new ListTag();
            chain.fateTargets().forEach(id -> targets.add(StringTag.valueOf(id.toString())));
            vm.getImage().getUserData().put("cmi_shared_fate_targets", targets);
            vm.getImage().getUserData().putString("cmi_effect_origins", chain.encodeOrigins());
            if(chain.partition()!=null)vm.getImage().getUserData().putString("cmi_partition",chain.partition().encode());
            else vm.getImage().getUserData().remove("cmi_partition");
            if (view.isStackClear() || failed) {
                vm.getImage().getUserData().remove("cmi_partition");
                vm.getImage().getUserData().remove("cmi_cast_chain");
                vm.getImage().getUserData().remove("cmi_shared_fate_targets");
                vm.getImage().getUserData().remove("cmi_effect_origins");
                CastChains.release(chain, chain);
            }
            return view;
        } finally {
            if (failed) CastChains.release(chain, chain);
            CastChains.release(chain, this);
        }
    }
}
