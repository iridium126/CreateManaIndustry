package com.iridium126.createmanaindustry.content.allaystorm;

import com.iridium126.createmanaindustry.content.allaystorm.network.ServerboundStormHitPacket;
import com.iridium126.createmanaindustry.mixin.vanilla.LivingEntityAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.stats.Stats;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.CommonHooks;

/** Admission and vanilla-shaped combat against a server-owned member HP entry, never a spawned entity. */
public final class StormMelee {
    private StormMelee() {}

    public static final class Attempts {
        private final long[] ticks = new long[12];
        private int cursor, count;
        private long lastTick = Long.MIN_VALUE, lastSequence;
        private Allay proxy;

        public boolean claim(long now, long sequence) {
            if (sequence <= lastSequence || sequence < 1) return false;
            lastSequence = sequence; // Rejected same-tick input must not become a deferred attack.
            if (now == lastTick || count == ticks.length && now - ticks[cursor] < 20) return false;
            ticks[cursor] = now; cursor = (cursor + 1) % ticks.length;
            count = Math.min(count + 1, ticks.length); lastTick = now;
            return true;
        }
        public void release(){proxy=null;}
        Allay proxy(ServerPlayer player, Vec3 contact, float health) {
            if (proxy == null || proxy.level() != player.level()) proxy = new Allay(EntityType.ALLAY, player.level());
            proxy.revive(); proxy.removeAllEffects(); proxy.clearFire(); proxy.setAbsorptionAmount(0);
            proxy.invulnerableTime = 0; proxy.hurtTime = 0; proxy.deathTime = 0;
            proxy.setDeltaMovement(Vec3.ZERO); proxy.fallDistance = 0; proxy.setTarget(null);
            proxy.setPos(contact); proxy.setHealth(health);
            return proxy;
        }
    }

    public record Result(float damage, float kbX, float kbZ, float light, boolean died,
            boolean crit, boolean magic, int hearts, SoundEvent attackSound) {}

    /** Null means the candidate did not deal damage. Invalid candidates never consume combat resources. */
    public static Result attack(ServerPlayer player, AllayStormData data, StormIdentity identity,
            ServerboundStormHitPacket packet, Attempts attempts) {
        var level = player.serverLevel();
        int member = packet.memberIdx();
        if (!data.active || !identity.equals(packet.identity()) || !identity.dimension().equals(level.dimension().location())
                || !player.isAlive() || player.isSpectator() || member < 0 || member >= data.count || data.dead.get(member)) return null;
        Vec3 contact = packet.contact();
        if (!Double.isFinite(contact.x) || !Double.isFinite(contact.y) || !Double.isFinite(contact.z)) return null;
        if (!attempts.claim(level.getGameTime(), packet.attackSeq())) return null;
        Allay proxy = attempts.proxy(player, contact, data.hpOf(member));
        if (!CommonHooks.onPlayerAttackTarget(player, proxy) || !proxy.isAttackable() || proxy.skipAttackInteraction(player)) return null;
        ItemStack weapon = player.getWeaponItem();
        var source = player.damageSources().playerAttack(player);
        float base = player.isAutoSpinAttack() ? ((LivingEntityAccessor) player).createmanaindustry$getAutoSpinAttackDmg()
                : (float) player.getAttributeValue(Attributes.ATTACK_DAMAGE);
        float bonus = EnchantmentHelper.modifyDamage(level, weapon, proxy, source, base) - base;
        float cooldown = player.getAttackStrengthScale(.5f);
        float damage = base * (.2f + cooldown * cooldown * .8f);
        bonus *= cooldown;
        if (damage <= 0 && bonus <= 0) { player.resetAttackStrengthTicker(); return null; }
        boolean charged = cooldown > .9f, sprint = charged && player.isSprinting();
        damage += weapon.getItem().getAttackDamageBonus(proxy, damage, source);
        boolean crit = charged && player.fallDistance > 0 && !player.onGround() && !player.onClimbable()
                && !player.isInWater() && !player.hasEffect(MobEffects.BLINDNESS) && !player.isPassenger() && !player.isSprinting();
        var critical = CommonHooks.fireCriticalHit(player, proxy, crit, crit ? 1.5f : 1);
        crit = critical.isCriticalHit();
        if (crit) damage *= critical.getDamageMultiplier();
        damage += bonus;
        float strength = (EnchantmentHelper.modifyKnockback(level, weapon, proxy, source,
                (float) player.getAttributeValue(Attributes.ATTACK_KNOCKBACK)) + (sprint ? 1 : 0)) * .5f;
        if (!Float.isFinite(damage) || damage <= 0 || !Float.isFinite(strength) || strength < 0) return null;
        float yaw = player.getYRot() * Mth.DEG_TO_RAD;
        float kbX = Mth.sin(yaw) * strength, kbZ = -Mth.cos(yaw) * strength;
        float before = data.hpOf(member), after = before - damage;
        boolean died = after <= 0;
        if (died) { data.dead.set(member); data.hp.remove(member); }
        else data.hp.put(member, after);
        proxy.setHealth(Math.max(0, after));
        if (strength > 0) {
            player.setDeltaMovement(player.getDeltaMovement().multiply(.6, 1, .6));
            player.setSprinting(false);
        }
        ItemStack copy = weapon.copy();
        boolean hurt = weapon.hurtEnemy(proxy, player);
        EnchantmentHelper.doPostAttackEffects(level, proxy, source);
        if (!weapon.isEmpty()) {
            if (hurt) weapon.postHurtEnemy(proxy, player);
            if (weapon.isEmpty()) {
                var hand = weapon == player.getMainHandItem() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
                net.neoforged.neoforge.event.EventHooks.onPlayerDestroyItem(player, copy, hand);
                player.setItemInHand(hand, ItemStack.EMPTY);
            }
        }
        float dealt = Math.min(before, damage);
        player.awardStat(Stats.DAMAGE_DEALT, Math.round(dealt * 10));
        player.causeFoodExhaustion(.1f);
        player.resetAttackStrengthTicker();
        player.swing(InteractionHand.MAIN_HAND, false);
        BlockPos pos = BlockPos.containing(contact);
        float light = level.getBrightness(LightLayer.BLOCK, pos) + 16 * level.getBrightness(LightLayer.SKY, pos);
        SoundEvent sound = crit ? SoundEvents.PLAYER_ATTACK_CRIT : charged ? SoundEvents.PLAYER_ATTACK_STRONG : SoundEvents.PLAYER_ATTACK_WEAK;
        if (sprint) sound = SoundEvents.PLAYER_ATTACK_KNOCKBACK;
        return new Result(damage, kbX, kbZ, light, died, crit, bonus > 0, dealt > 2 ? Math.min(63, (int)(dealt * .5f)) : 0, sound);
    }
}
