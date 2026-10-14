package com.iridium126.createmanaindustry.compat.trickster;

import at.petrak.hexcasting.api.casting.eval.MishapEnvironment;
import at.petrak.hexcasting.api.casting.mishaps.Mishap;
import at.petrak.hexcasting.common.lib.HexDamageTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Stock mishap operations on the recipient's own attributes, never invented attributes. */
final class SharedFateMishapEnv extends MishapEnvironment {
    private final Entity target;
    SharedFateMishapEnv(Entity target) {
        super((ServerLevel)target.level(),target instanceof ServerPlayer p?p:null);this.target=target;
    }
    public void yeetHeldItemsTowards(Vec3 position) {
        if(!(target instanceof LivingEntity living))return;
        Vec3 delta=position.subtract(living.position()).normalize().scale(0.5);
        for(InteractionHand hand:InteractionHand.values()) {
            var stack=living.getItemInHand(hand);
            if(stack.isEmpty())continue;
            living.setItemInHand(hand,ItemStack.EMPTY);yeetItem(stack,living.position(),delta);
        }
    }
    public void dropHeldItems() {yeetHeldItemsTowards(target.position().add(target.getLookAngle()));}
    public void drown() {
        if(target instanceof LivingEntity living) {
            if(living.getAirSupply()<200)living.hurt(living.damageSources().drown(),2);
            living.setAirSupply(0);
        }
    }
    public void damage(float proportion) {
        if(target instanceof LivingEntity living)
            Mishap.trulyHurt(living,living.damageSources().source(HexDamageTypes.OVERCAST),living.getHealth()*proportion);
    }
    public void removeXp(int amount) {if(target instanceof ServerPlayer player)player.giveExperiencePoints(-amount);}
    public void blind(int ticks) {if(target instanceof LivingEntity living)living.addEffect(new MobEffectInstance(MobEffects.BLINDNESS,ticks));}
    public void nauseate(int ticks) {if(target instanceof LivingEntity living)living.addEffect(new MobEffectInstance(MobEffects.CONFUSION,ticks));}
}
