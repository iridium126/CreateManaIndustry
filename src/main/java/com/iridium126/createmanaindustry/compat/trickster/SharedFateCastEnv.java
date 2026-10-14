package com.iridium126.createmanaindustry.compat.trickster;

import at.petrak.hexcasting.api.casting.eval.*;
import at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv;
import at.petrak.hexcasting.api.casting.ParticleSpray;
import at.petrak.hexcasting.api.pigment.FrozenPigment;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.function.Predicate;

/** Complete replay environment, without upstream-caster resources or casting state. */
final class SharedFateCastEnv extends CastingEnvironment {
    private final Entity target;
    private final StaffCastEnv playerEnv;
    private final MishapEnvironment mishaps;
    SharedFateCastEnv(Entity target) {
        super((ServerLevel)target.level());this.target=target;
        playerEnv=target instanceof ServerPlayer player?new StaffCastEnv(player,InteractionHand.MAIN_HAND):null;
        mishaps=playerEnv==null?new SharedFateMishapEnv(target):playerEnv.getMishapEnvironment();
    }
    public LivingEntity getCastingEntity(){return target instanceof LivingEntity living?living:null;}
    public MishapEnvironment getMishapEnvironment(){return mishaps;}
    public Vec3 mishapSprayPos(){return target.position();}
    public long extractMediaEnvironment(long cost,boolean simulate){return playerEnv==null?cost:playerEnv.extractMediaEnvironment(cost,simulate);}
    public boolean isVecInRangeEnvironment(Vec3 position){return playerEnv==null?target.position().distanceToSqr(position)<=32*32:playerEnv.isVecInRangeEnvironment(position);}
    public boolean hasEditPermissionsAtEnvironment(BlockPos pos){return playerEnv!=null&&playerEnv.hasEditPermissionsAt(pos);}
    public InteractionHand getCastingHand(){return InteractionHand.MAIN_HAND;}
    public List<ItemStack> getUsableStacks(StackDiscoveryMode mode){return playerEnv==null?getPrimaryStacks().stream().map(HeldItemInfo::stack).toList():playerEnv.getUsableStacks(mode);}
    public List<HeldItemInfo> getPrimaryStacks(){
        if(playerEnv!=null)return playerEnv.getPrimaryStacks();
        return target instanceof LivingEntity living?List.of(new HeldItemInfo(living.getOffhandItem(),InteractionHand.OFF_HAND),
                new HeldItemInfo(living.getMainHandItem(),InteractionHand.MAIN_HAND)):List.of();
    }
    public boolean replaceItem(Predicate<ItemStack> predicate,ItemStack stack,InteractionHand hand){
        if(playerEnv!=null)return playerEnv.replaceItem(predicate,stack,hand);
        if(target instanceof LivingEntity living)for(InteractionHand candidate:InteractionHand.values()){
            if((hand==null||hand==candidate)&&predicate.test(living.getItemInHand(candidate))){living.setItemInHand(candidate,stack);return true;}
        }
        return false;
    }
    public FrozenPigment getPigment(){return playerEnv==null?FrozenPigment.DEFAULT.get():playerEnv.getPigment();}
    public FrozenPigment setPigment(FrozenPigment pigment){return playerEnv==null?null:playerEnv.setPigment(pigment);}
    public void produceParticles(ParticleSpray spray,FrozenPigment pigment){spray.sprayParticles(world,pigment);}
    public void printMessage(Component message){if(target instanceof ServerPlayer player)player.sendSystemMessage(message);}
    protected boolean isCreativeMode(){return target instanceof ServerPlayer player&&player.isCreative();}
}
