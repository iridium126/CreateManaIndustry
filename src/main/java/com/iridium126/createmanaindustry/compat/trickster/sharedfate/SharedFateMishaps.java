package com.iridium126.createmanaindustry.compat.trickster.sharedfate;

import com.iridium126.createmanaindustry.compat.trickster.casting.CastChain;
import com.iridium126.createmanaindustry.compat.trickster.casting.CastChains;

import at.petrak.hexcasting.api.casting.eval.*;
import at.petrak.hexcasting.api.casting.eval.vm.*;
import at.petrak.hexcasting.api.casting.mishaps.Mishap;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.utils.TreeList;
import at.petrak.hexcasting.common.msgs.MsgNewSpellPatternS2C;
import at.petrak.hexcasting.xplat.IXplatAbstractions;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.InteractionHand;
import java.util.*;

/** One dispatch per original incident; node execution is guarded against recursive interception. */
public final class SharedFateMishaps {
    private static final ThreadLocal<ArrayDeque<CastingVM>> VMS=ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Boolean> REPLAY=ThreadLocal.withInitial(()->false);
    private static final ThreadLocal<IdentityHashMap<CastingVM,CastChain>> VM_CHAINS=ThreadLocal.withInitial(IdentityHashMap::new);
    private SharedFateMishaps(){}
    public static CastChains.Scope enterVm(CastingVM vm){
        VMS.get().push(vm);return ()->{VMS.get().pop();if(VMS.get().isEmpty())VMS.remove();};
    }
    public static CastChains.Scope enterChain(CastingVM vm,CastChain chain){
        var map=VM_CHAINS.get();CastChain previous=map.put(vm,chain);
        return ()->{if(previous==null)map.remove(vm);else map.put(vm,previous);if(map.isEmpty())VM_CHAINS.remove();};
    }
    public static void perform(CastingVM original,Mishap mishap,Mishap.Context error,Runnable executeOriginal){
        if(selfNameExempt(original.getEnv().getCastingEntity(),mishap))return;
        if(REPLAY.get()||!(original.getEnv().getCastingEntity() instanceof ServerPlayer caster)){executeOriginal.run();return;}
        var visited=new HashSet<UUID>();visited.add(caster.getUUID());
        ServerLevel incidentWorld=caster.serverLevel();
        var queue=new ArrayDeque<UUID>(SharedFateManager.outgoing(caster.getServer(),caster.getUUID()));
        REPLAY.set(true);
        try{
            executeOriginal.run();
            while(!queue.isEmpty()){
                UUID id=queue.removeFirst();if(!visited.add(id))continue;
                Entity target=incidentWorld.getEntity(id);
                if(target==null||!target.isAlive()||target.isRemoved())continue;
                // Capture before damage / VM reset releases this recipient's bindings.
                List<UUID> next=SharedFateManager.outgoing(caster.getServer(),id);
                try{replay(target,mishap,error);}catch(RuntimeException|LinkageError failure){
                    CreateManaIndustry.LOGGER.warn("Shared-fate mishap replay failed for {}",id,failure);
                }
                queue.addAll(next);
            }
        }finally{REPLAY.remove();}
    }
    private static boolean selfNameExempt(Entity target,Mishap mishap){
        return target instanceof ServerPlayer player
                && mishap instanceof at.petrak.hexcasting.api.casting.mishaps.MishapOthersName name
                && name.getConfidant()==player&&CastChains.nameExempt(player.getServer(),player.getUUID());
    }
    private static CastingVM active(Entity target){
        for(CastingVM vm:VMS.get())if(vm.getEnv().getWorld()==target.level()&&vm.getEnv().getCastingEntity()==target)return vm;
        return null;
    }
    private static void replay(Entity target,Mishap mishap,Mishap.Context error){
        if(selfNameExempt(target,mishap))return;
        CastingVM vm=active(target);boolean stored=vm==null&&target instanceof ServerPlayer;
        if(stored)vm=IXplatAbstractions.INSTANCE.getStaffcastVM((ServerPlayer)target,InteractionHand.MAIN_HAND);
        CastingImage image=vm==null?new CastingImage():vm.getImage();
        CastingEnvironment environment=target instanceof ServerPlayer player
                ?new at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv(player,InteractionHand.MAIN_HAND)
                :new SharedFateCastEnv(target);
        CastChain own=vm==null?null:VM_CHAINS.get().get(vm);
        if(own==null&&target instanceof ServerPlayer player)own=CastChains.find(player,image.getUserData().getString("cmi_cast_chain"));
        try(var ignored=CastChains.enter(own)){
            var spray=mishap.particleSpray(environment);
            spray.sprayParticles(environment.getWorld(),mishap.accentColor(environment,error));
            spray.sprayParticles(environment.getWorld(),new at.petrak.hexcasting.api.pigment.FrozenPigment(
                    new net.minecraft.world.item.ItemStack(at.petrak.hexcasting.common.lib.HexItems.DYE_PIGMENTS.get(net.minecraft.world.item.DyeColor.RED).get()),
                    net.minecraft.Util.NIL_UUID));
            TreeList<Iota> stack=mishap.execute(environment,error,image.getStack());
            CastingImage changed=image.copy(stack,image.getParenCount(),image.getParenthesized(),image.getEscapeNext(),
                    image.getSimulateNext(),image.getOpsConsumed(),image.getUserData());
            if(vm!=null)vm.setImage(changed);
            if(target instanceof ServerPlayer player){
                if(stored)IXplatAbstractions.INSTANCE.setStaffcastImage(player,stack.isEmpty()?null:changed);
                var view=new ExecutionClientView(stack.isEmpty(),mishap.resolutionType(environment),new ArrayList<>(stack),null);
                IXplatAbstractions.INSTANCE.sendPacketToPlayer(player,new MsgNewSpellPatternS2C(view,-1));
                var message=mishap.errorMessageWithName(environment,error);
                if(message!=null)player.sendSystemMessage(message);
            }
        }
    }
}
