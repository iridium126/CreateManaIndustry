package com.iridium126.createmanaindustry.client.particles.interaction;
import com.iridium126.createmanaindustry.client.particles.engine.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import com.iridium126.createmanaindustry.client.allaystorm.AllayStormRuntime;
import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;
import com.iridium126.createmanaindustry.mixin.vanilla.MinecraftInvoker;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ConditionalEffect;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.enchantment.effects.EnchantmentValueEffect;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.CommonHooks;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms;
import static com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.*;
import org.lwjgl.BufferUtils;

/** Owns MODEL picking, local melee prediction, damage inputs and combat presentation. */
public final class ModelInteractionRuntime {


    // ---- vanilla combat particles (crit / enchanted-hit / heart / poof) ----
    /**
     * Crit/enchanted-hit tracking window: vanilla {@code TrackingEmitter}
     * follows the target for 3 ticks (0.15 s), rejection-sampling ~16 points
     * per tick around its AABB (~25 accepted particles in total). The engine
     * re-sends one small {@code originRef} command per frame over the window;
     * emit.comp resolves the source allay's CURRENT pool position, so the
     * stars track the knockback like vanilla. Storm sources carry their MEMBER
     * identity and resolve through the identity map (rebuild per dispatch) —
     * immune to pool recompaction; non-storm sources resolve their stable token through
     * the committed MODEL population and validated hit approval. Storm stop / clear invalidate the
     * window immediately (cleared map = absent entries = spawns dropped).
     */
    private static final class TrackingBurst {
        static final double DURATION = 0.15;
        static final double RATE = 25.0 / DURATION; // accepted particles per second

        final int emitId;
        final int localToken;
        /** Storm member identity (-1 = non-storm source); carries the ride across pool recompaction. */
        final int memberIdx;
        final float light;
        double remaining = RATE * DURATION;
        double elapsed = 0;
        double acc = 0;

        TrackingBurst(int emitId, int localToken, int memberIdx, float light) {
            this.emitId = emitId;
            this.localToken = localToken;
            this.memberIdx = memberIdx;
            this.light = light;
        }
    }

    /** One-shot combat burst (damage-indicator hearts), drained next compute. */
    private record CombatBurst(int emitId, int localToken, int memberIdx, float light, int count) {
    }

    private final List<TrackingBurst> trackings = new ArrayList<>();
    private final List<CombatBurst> combatBursts = new ArrayList<>();

    // ---- player melee (HP / hit-query) state --------------------------------
    /**
     * Damage-queue staging buffer: uvec4 count header (x = entry count) plus
     * vec4 entries ({index, damage, kbVecX, kbVecZ}) -- matches the std430
     * block in update.comp. Written on click, uploaded before the next update
     * dispatch, followed by one zero-count upload to clear (single buffer,
     * stream-order safe: the GL command stream orders this frame's update
     * before the next frame's header rewrite).
     */
    private final ByteBuffer dmgTmp = BufferUtils.createByteBuffer(
            ParticleBuffers.DAMAGE_HEADER_BYTES + ParticleBuffers.DAMAGE_QUEUE_CAP * ParticleBuffers.DAMAGE_ENTRY_BYTES);
    private int dmgCount = 0;
    private boolean dmgPending = false;
    private boolean dmgClearNext = false;
    // Shared hit data is owned here; Storm keeps its server-authority rules.
    /**
     * Formal attack target for the vanilla damage pipeline (enchantment type
     * queries, CriticalHitEvent, item damage bonus). Never ticked or added to
     * the world; recreated when the level changes.
     */
    private Allay proxyTarget = null;
    private ClientLevel proxyLevel = null;
    /**
     * The vanilla crosshair pick result as it stood BEFORE the last synthetic
     * injection ({@link #injectCrosshairPick} only overwrites it when an allay
     * particle is strictly closer) — exactly what a world without the particle
     * would have produced. The use-key fall-through ({@link #replayVanillaUse})
     * re-runs the click against it; refreshed every injection, so it is never
     * stale beyond one frame while the crosshair sits on the proxy.
     */
    private HitResult vanillaPickResult = null;
    private long lastHitGeneration=-1;
    private boolean hitRayCurrent(){return lastHitRay!=null&&lastHitGeneration>=0;}

    // dispatchStormPosReadback / collectSyncedPlayers moved to AllayStormRuntime
    // (the engine call site computes the pool-census dispatch bound and hands it
    // over; the GL dispatch itself is the storm runtime's).

    /**
     * Crosshair-pick injection ({@code GameRenderer.pick} tail mixin): makes
     * the GPU allay particles win the crosshair like real entities. The
     * vanilla consumers then drive everything for free — {@code Gui} renders
     * the attack crosshair and the charge meter ({@code crosshairPickEntity}
     * != null), {@code LevelRenderer} outlines only BlockHitResults (the wall
     * behind an allay is no longer picked through), and
     * {@code Minecraft.continueAttack} mines only BLOCK hits (holding attack
     * on an allay never mines through it). Vanilla picks the NEARER of its
     * entity/block results; the allay replaces the current result only when
     * strictly closer, so real entities and nearer walls keep winning exactly
     * like vanilla — occlusion included, with no raycast here (a wall in
     * front yields a closer block hit and the allay loses). hit.comp casts
     * from the player's eye along the camera rotation — the same ray vanilla
     * picks with — at the same {@code entityInteractionRange()} bound.
     * <p>
     * The injected entity is the client-side proxy ({@link #proxyFor}): a
     * stray vanilla attack on it (possible only when the click snapshot was
     * just consumed) sends an unknown-entity interact packet the server
     * ignores, and {@code LivingEntity.hurt} early-returns on the client. A
     * vanilla USE on it would be far worse — {@code startUseItem} runs the
     * proxy's {@code Allay.mobInteract} as client-side prediction and hands
     * the held item to a throwaway object — so the pre-injection result is
     * kept ({@code vanillaPickResult}) and the use key replays against it
     * ({@link #replayVanillaUse}); real entities never route through the
     * gate. The read is NON-consuming ({@link #crosshairHitKey}), so the
     * crosshair never flickers in the frame between a click and the next
     * fence poll.
     */
    public void injectCrosshairPick(Minecraft mc) {
        if (!particles.available() || !ClientConfig.particleEnabled
                || crosshairHitKey() == ParticleBuffers.HIT_MISS || !hitRayCurrent())
            return;
        if (mc.level == null || mc.player == null || mc.player.isSpectator()
                || mc.getCameraEntity() != mc.player)
            return;
        float t = (crosshairHitKey() >>> ParticleBuffers.HIT_KEY_INDEX_BITS) / 32.0F;
        // same ray as dispatchHitQuery/hit.comp — player eye origin, camera
        // rotation direction (vanilla's pick also casts from the camera
        // ENTITY's eye, not the third-person camera) — so the injected ray
        // parameter and the block/entity distance comparison below share one
        // exact base
        Vec3 eye = lastHitRay.origin();
        Vec3 look = lastHitRay.direction();
        Vec3 hitPos = eye.add(look.scale(t));
        HitResult current = mc.hitResult;
        double currentDistSq = current == null || current.getType() == HitResult.Type.MISS
                ? Double.MAX_VALUE
                : current.getLocation().distanceToSqr(eye);
        if ((double) t * (double) t >= currentDistSq)
            return; // a nearer vanilla result (real entity or wall) keeps winning
        this.vanillaPickResult = current;
        Allay proxy = this.proxyFor(mc.level);
        proxy.setPos(hitPos.x, hitPos.y, hitPos.z);
        mc.hitResult = new EntityHitResult(proxy, hitPos);
        mc.crosshairPickEntity = proxy;
    }

    /**
     * True when {@code entity} is the client-side crosshair proxy — the only
     * entity {@link #injectCrosshairPick} can ever surface to vanilla
     * consumers (real entities never pass through here; the check is plain
     * object identity).
     */
    public boolean isSyntheticPickTarget(Entity entity) {
        return entity != null && entity == this.proxyTarget;
    }

    /**
     * Use-key fall-through for the synthetic crosshair pick. The injection
     * makes vanilla consumers see an ENTITY hit on the client-side proxy, and
     * {@code Minecraft.startUseItem} would run the proxy's vanilla
     * {@code Allay.mobInteract} as client-side prediction — handing the held
     * item to a throwaway object (count desyncs, ITEM_GIVEN plays, the
     * server never knows). The storm allay is NOT an entity: the click is
     * re-run against the pre-injection vanilla result ({@code
     * vanillaPickResult}) exactly as if the particle weren't there — real
     * entities and blocks behind the allay respond normally, and a null
     * result degrades to a plain item use (a vanilla-shaped MISS). The
     * re-entrant {@code startUseItem} never re-enters this path: the
     * restored result is never the proxy (vanilla pick cannot see it — it is
     * not in the level's entity list).
     */
    public void replayVanillaUse(Minecraft mc) {
        HitResult vanilla = this.vanillaPickResult;
        mc.hitResult = vanilla != null ? vanilla
                : BlockHitResult.miss(Vec3.ZERO, Direction.UP, BlockPos.ZERO);
        ((MinecraftInvoker) (Object) mc).createmanaindustry$invokeStartUseItem();
    }

    /**
     * Client attack-key hook ({@code InputEvent.InteractionKeyMappingTriggered}):
     * consumes the newest completed hit-query snapshot and performs a
     * vanilla-aligned melee attack when an allay is under the crosshair. The
     * damage application is split by target: STORM members are REPORTED to
     * the server (authoritative HP + death there; this client applies the
     * broadcast like everyone else), while non-storm MODEL debug particles
     * keep the legacy local-authority queue. Cosmetic combat particles and
     * sounds play locally either way. Returns true when the click was
     * consumed -- the caller must cancel the
     * vanilla handling (which would otherwise only play a miss swing).
     */
    public boolean handlePlayerAttack() {
        if (!particles.available() || !ClientConfig.particleEnabled)
            return false;
        int key = hitKeySnapshot();
        if (key == ParticleBuffers.HIT_MISS || !hitRayCurrent())
            return false;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (mc.level == null || player == null || player.isSpectator())
            return false;
        if(mc.hitResult instanceof EntityHitResult entityHit&&entityHit.getEntity()!=proxyTarget)return false;
        int idx = key & ((1 << ParticleBuffers.HIT_KEY_INDEX_BITS) - 1);
        float t = (key >>> ParticleBuffers.HIT_KEY_INDEX_BITS) / 32.0F;
        if (idx >= particles.capacity())
            return false;
        // vanilla pick semantics: the eye ray is clipped by the first solid
        // block, so an allay behind a wall is untargetable. One cheap CPU
        // raycast against the real world (exact collision shapes, works
        // outside the collision bake volumes too).
        Vec3 eye = lastHitRay.origin();
        Vec3 hitPos = lastHitRay.point(Math.max(t, 0.01F));
        BlockHitResult clip = mc.level.clip(new ClipContext(eye, hitPos,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (clip.getType() != HitResult.Type.MISS
                && clip.getLocation().distanceToSqr(eye) < eye.distanceToSqr(hitPos) - 1.0E-4)
            return false; // a block is closer than the allay: wall between
        int member=hitMemberIdx(),token=hitToken();
        if(member<0&&token==0)return false;
        boolean attacked = syntheticAttack(mc, player, idx, hitPos,member,token,hitHp());
        if (attacked)
            // consume-once: never let one snapshot fire twice between fence
            // refreshes (two rapid clicks must not double-hit one allay)
            consumeHitSnapshot();
        return attacked;
    }

    /**
     * Storm candidates return immediately after report + swing prediction.
     * Ordinary MODEL particles use a client-side mirror of {@code Player.attack}:
     * cooldown scaling
     * on the base damage (0.2 + f²·0.8), the same scaling on the enchantment
     * bonus, the UNCOOLED weapon bonus, the ×1.5 crit through the NeoForge
     * CriticalHitEvent, the STRONG/WEAK hit sound split, knockback-gated
     * attacker slowdown, per-hit food exhaustion, and the cooldown reset at
     * the END (NeoForge moved it there so the scale is accurate during the
     * attack). The SERVER half of the pipeline (weapon durability via
     * hurtEnemy/postHurtEnemy, DAMAGE_DEALT stat) runs in
     * {@code StormManager.handleHit} — vanilla consumes durability
     * server-side only. Documented deviations: sweep absent (no multi-target
     * hit reports); POST_ATTACK enchantment effects absent HERE by design —
     * vanilla runs them server-side only ({@code doPostAttackEffects} is
     * ServerLevel-gated), and the storm's server half applies them in
     * {@code StormManager.handleHit} (Wind Burst launches the attacker
     * there); no invulnerability frames (snapshot-paced hits are the boss
     * design).
     */
    private boolean syntheticAttack(Minecraft mc, LocalPlayer player, int idx, Vec3 hitPos, int memberIdx,int token,float hpSnapshot) {
        if (memberIdx >= 0 && AllayStormRuntime.INSTANCE.active()) {
            // Two-argument swing is local prediction; no vanilla swing packet may reset server cooldown.
            com.iridium126.createmanaindustry.client.allaystorm.StormFeedback.INSTANCE.reportHit(memberIdx, hitPos);
            player.swing(InteractionHand.MAIN_HAND, false);
            player.resetAttackStrengthTicker();
            return true;
        }
        Allay target = proxyFor(mc.level);
        ItemStack weapon = player.getWeaponItem();
        DamageSource source = player.damageSources().playerAttack(player);
        float base = player.isAutoSpinAttack()
                ? ((com.iridium126.createmanaindustry.mixin.vanilla.LivingEntityAccessor) (Object) player)
                        .createmanaindustry$getAutoSpinAttackDmg()
                : clientAttackAttributeValue(player, weapon, Attributes.ATTACK_DAMAGE);
        float enchBonus = enchantValueEffect(EnchantmentEffectComponents.DAMAGE, weapon, base, player) - base;
        float cooldown = player.getAttackStrengthScale(0.5F);
        float damage = base * (0.2F + cooldown * cooldown * 0.8F);
        enchBonus *= cooldown;

        if (damage <= 0.0F && enchBonus <= 0.0F) {
            // vanilla plays NOTHING here (PLAYER_ATTACK_NODAMAGE is the
            // hurt()-rejected sound, a case the GPU target has no analog of);
            // the swing still happens and the ticker still resets
            player.swing(InteractionHand.MAIN_HAND);
            player.resetAttackStrengthTicker();
            return true;
        }

        boolean fullCharge = cooldown > 0.9F;
        boolean sprintKnockback = false;
        if (player.isSprinting() && fullCharge) {
            mc.level.playLocalSound(player.getX(), player.getY(), player.getZ(),
                    SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.PLAYERS, 1.0F, 1.0F, false);
            sprintKnockback = true;
        }

        damage += weapon.getItem().getAttackDamageBonus(target, damage, source);
        boolean crit = fullCharge
                && player.fallDistance > 0.0F
                && !player.onGround()
                && !player.onClimbable()
                && !player.isInWater()
                && !player.hasEffect(MobEffects.BLINDNESS)
                && !player.isPassenger()
                && !player.isSprinting();
        var critEvent = CommonHooks.fireCriticalHit(player, target, crit, crit ? 1.5F : 1.0F);
        crit = critEvent.isCriticalHit();
        if (crit) {
            damage *= critEvent.getDamageMultiplier();
            mc.level.playLocalSound(player.getX(), player.getY(), player.getZ(),
                    SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.0F, 1.0F, false);
        } else {
            // vanilla: STRONG on a full charge, WEAK otherwise (the gate is
            // !crit && !sweep — the sweep branch never applies here, and a
            // sprint-knockback hit still falls through to STRONG/WEAK)
            mc.level.playLocalSound(player.getX(), player.getY(), player.getZ(),
                    fullCharge ? SoundEvents.PLAYER_ATTACK_STRONG : SoundEvents.PLAYER_ATTACK_WEAK,
                    SoundSource.PLAYERS, 1.0F, 1.0F, false);
        }

        float total = damage + enchBonus;

        // Vanilla combat particles, client-mirrored. LocalPlayer.crit/magicCrit
        // fire ONLY on the attacking client (a TrackingEmitter riding the
        // target for 3 ticks); the damage hearts mirror the server's
        // sendParticles (f8 > 2.0 hearts, count = f8·0.5). Spawns resolve
        // against the GPU pool at the next compute pass, at the light sampled
        // here — allays are not real entities, so vanilla draws none of these.
        // Storm members ride their MEMBER identity across pool recompaction
        // (the map in emit.comp resolves it per dispatch); non-storm sources
        // keep the legacy pool-index path.
        float light = sampleLight(mc.level, hitPos);
        boolean magic = enchBonus > 0.0F;
        int hearts = total > 2.0F ? (int) (total * 0.5F) : 0;
        if (crit) {
            int cid = particles.ensureCombatEmitter(CombatSpecs.CRIT);
            if (cid >= 0)
                this.trackings.add(new TrackingBurst(cid, token, memberIdx, light));
        }
        if (magic) {
            int mid = particles.ensureCombatEmitter(CombatSpecs.MAGIC);
            if (mid >= 0)
                this.trackings.add(new TrackingBurst(mid, token, memberIdx, light));
        }
        if (hearts > 0) {
            int hid = particles.ensureCombatEmitter(CombatSpecs.HEART);
            if (hid >= 0)
                this.combatBursts.add(new CombatBurst(hid, token, memberIdx, light, hearts));
        }

        // vanilla knockback (LivingEntity.knockback, airborne target: y is
        // untouched); the strength is pre-multiplied into the direction so a
        // queue entry stays 16 bytes
        float kb = enchantValueEffect(EnchantmentEffectComponents.KNOCKBACK, weapon,
                clientAttackAttributeValue(player, weapon, Attributes.ATTACK_KNOCKBACK), player) + (sprintKnockback ? 1.0F : 0.0F);
        float strength = kb * 0.5F;
        float yawRad = player.getYRot() * (float) (Math.PI / 180.0);
        float kbVecX = Mth.sin(yawRad) * strength;
        float kbVecZ = -Mth.cos(yawRad) * strength;

        // target sound chosen from the SNAPSHOT hp (at most one frame old;
        // regen drift is a few tenths of an HP at worst)
        boolean lethal = hpSnapshot - total <= 0.0F;
        mc.level.playLocalSound(hitPos.x, hitPos.y, hitPos.z,
                lethal ? SoundEvents.ALLAY_DEATH : SoundEvents.ALLAY_HURT,
                SoundSource.NEUTRAL, 1.0F, 1.0F, false);

        // vanilla gates BOTH the attacker slowdown and the sprint stop on a
        // nonzero knockback (f4 > 0): a bare-handed non-sprint hit applies no
        // feedback at all — mirror that
        if (strength > 0.0F) {
            player.setDeltaMovement(player.getDeltaMovement().multiply(0.6, 1.0, 0.6));
            player.setSprinting(false);
        }

        // vanilla exhausts 0.1 per landed hit (Player.attack, both halves);
        // the ordinary MODEL path lands every consumed snapshot
        player.causeFoodExhaustion(0.1F);
        // legacy local-authority path (non-storm MODEL debug particles)
        Vec3 look=Vec3.directionFromRotation(mc.gameRenderer.getMainCamera().getXRot(),mc.gameRenderer.getMainCamera().getYRot());
        float reach=(float)player.entityInteractionRange();Vec3 eye=player.getEyePosition();
        // Do not clamp to the proxy's quantized distance (it can lie just before the actual AABB).
        HitResult nearer=mc.crosshairPickEntity==target?vanillaPickResult:mc.hitResult;
        if(nearer!=null&&nearer.getType()!=HitResult.Type.MISS)reach=Math.min(reach,(float)eye.distanceTo(nearer.getLocation()));
        var wall=mc.level.clip(new ClipContext(eye,eye.add(look.scale(reach)),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,player));
        if(wall.getType()!=HitResult.Type.MISS)reach=Math.min(reach,(float)eye.distanceTo(wall.getLocation()));
        enqueueDamageEntry(idx,total,kbVecX,kbVecZ,light,0f,token,eye,look,reach);
        player.swing(InteractionHand.MAIN_HAND);
        player.resetAttackStrengthTicker();
        return true;
    }

    /**
     * Client-side mirror of {@code LivingEntity.getAttributeValue} for the
     * attack attributes. {@code ATTACK_DAMAGE}/{@code ATTACK_KNOCKBACK} are
     * registered NON-syncable (no {@code setSyncable(true)} in
     * {@code Attributes}), and BOTH modifier sources that feed them are
     * applied server-side only — equipment via {@code collectEquipmentChanges}
     * (runs under {@code !isClientSide}) and mob effects via
     * {@code onEffectAdded} (ditto) — so the live client attribute always
     * reads the bare base value (1.0 for a player). Vanilla never minds (it
     * recomputes server-side for real entities), but ordinary MODEL particles calculate damage locally,
     * so their mirror must include weapon
     * MAINHAND modifiers from the item component (vanilla's own client-side
     * precedent, {@code Mob.getApproximateAttackDamageWithItem}) plus
     * mob-effect modifiers via {@code MobEffect.createModifiers}, applied in
     * vanilla {@code AttributeInstance} operation order over the base value.
     */
    private static float clientAttackAttributeValue(LocalPlayer player, ItemStack weapon,
            Holder<Attribute> attribute) {
        double baseValue = player.getAttributeBaseValue(attribute);
        final double[] acc = new double[3]; // ADD_VALUE, ADD_MULTIPLIED_BASE, ADD_MULTIPLIED_TOTAL
        java.util.function.BiConsumer<Holder<Attribute>, AttributeModifier> collector = (attr, mod) -> {
            if (attr.value() == attribute.value()) {
                switch (mod.operation()) {
                    case ADD_VALUE -> acc[0] += mod.amount();
                    case ADD_MULTIPLIED_BASE -> acc[1] += mod.amount();
                    case ADD_MULTIPLIED_TOTAL -> acc[2] += mod.amount();
                }
            }
        };
        if (!weapon.isEmpty())
            weapon.getAttributeModifiers().forEach(EquipmentSlot.MAINHAND, collector);
        for (MobEffectInstance effect : player.getActiveEffects())
            effect.getEffect().value().createModifiers(effect.getAmplifier(), collector);
        return (float) ((baseValue + acc[0]) * (1.0 + acc[1]) * (1.0 + acc[2]));
    }

    /**
     * Client-side equivalent of {@code EnchantmentHelper.modifyDamage} /
     * {@code modifyKnockback}: those need a ServerLevel, so iterate the
     * weapon's enchantments directly and apply UNCONDITIONAL value effects in
     * order. Exact for sharpness and knockback (unconditional in vanilla);
     * conditional effects (smite/bane/impaling) are skipped -- their
     * target-type conditions can never pass for an allay anyway, and modded
     * conditional damage enchantments are a documented gap.
     */
    private static float enchantValueEffect(
            DataComponentType<List<ConditionalEffect<EnchantmentValueEffect>>> component,
            ItemStack tool, float value, LocalPlayer player) {
        ItemEnchantments ench = tool.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        for (Holder<Enchantment> holder : ench.keySet()) {
            int level = ench.getLevel(holder);
            for (ConditionalEffect<EnchantmentValueEffect> effect : holder.value().getEffects(component)) {
                if (effect.requirements().isEmpty())
                    value = effect.effect().process(level, player.getRandom(), value);
            }
        }
        return value;
    }

    /**
     * Formal attack target for the vanilla damage pipeline (enchantment type
     * queries, the NeoForge CriticalHitEvent, item damage bonus). Never ticked
     * or added to the world -- client-side state only, recreated on level change.
     */
    private Allay proxyFor(ClientLevel level) {
        if (this.proxyTarget == null || this.proxyLevel != level) {
            this.proxyTarget = new Allay(EntityType.ALLAY, level);
            this.proxyLevel = level;
            this.vanillaPickResult = null;
        }
        return this.proxyTarget;
    }

    /**
     * Queues one melee-damage entry for the next update dispatch (render
     * thread). Shared writer for BOTH damage paths: the legacy local-authority
     * entries (key = pool index, flags 0) and the storm member entries (key =
     * memberIdx, {@link ParticleBuffers#DAMAGE_FLAG_MEMBER} plus the server's
     * death bit — flags are protocol semantics, computed by the caller
     * {@code AllayStormRuntime.applyStormDamage}). Absolute writes: the
     * buffer's limit may have been shrunk by the last upload, so restore it to
     * capacity first (absolute puts bounds-check against the limit, not the
     * capacity). The entry carries the spawn-time packed light; update.comp
     * stashes it in the target's p1.w so the death chain's poof burst lights
     * like the hit did.
     */
    public void enqueueDamageEntry(int key, float damage, float kbVecX, float kbVecZ, float light, float flags) {
        enqueueDamageEntry(key,damage,kbVecX,kbVecZ,light,flags,0,null,null,0);
    }
    private final com.iridium126.createmanaindustry.client.gpu.profile.GpuQueueAccounting damageAccounting=new com.iridium126.createmanaindustry.client.gpu.profile.GpuQueueAccounting();
    private void enqueueDamageEntry(int key,float damage,float kbVecX,float kbVecZ,float light,float flags,int token,Vec3 eye,Vec3 look,float reach){
        if (this.dmgCount >= ParticleBuffers.DAMAGE_QUEUE_CAP) {
            damageAccounting.rejected(1);return;
        }
        damageAccounting.accepted(1);
        if (this.dmgTmp.limit() != this.dmgTmp.capacity())
            this.dmgTmp.limit(this.dmgTmp.capacity());
        int off = ParticleBuffers.DAMAGE_HEADER_BYTES + this.dmgCount * ParticleBuffers.DAMAGE_ENTRY_BYTES;
        this.dmgTmp.putFloat(off, (float) key);
        this.dmgTmp.putFloat(off + 4, damage);
        this.dmgTmp.putFloat(off + 8, kbVecX);
        this.dmgTmp.putFloat(off + 12, kbVecZ);
        this.dmgTmp.putFloat(off + 16, light);
        this.dmgTmp.putFloat(off + 20, flags);
        this.dmgTmp.putInt(off + 24, token);
        this.dmgTmp.putInt(off + 28, 0);
        for(int word=8;word<16;word++)dmgTmp.putInt(off+word*4,0);
        if(eye!=null){dmgTmp.putFloat(off+32,(float)eye.x).putFloat(off+36,(float)eye.y).putFloat(off+40,(float)eye.z).putFloat(off+44,reach);
            dmgTmp.putFloat(off+48,(float)look.x).putFloat(off+52,(float)look.y).putFloat(off+56,(float)look.z).putInt(off+60,1);}
        this.dmgCount++;
        this.dmgPending = true;
    }

    /**
     * Relay-side combat visuals: a storm hit broadcast whose attacker is
     * someone else spawns the same vanilla set the attacker drew locally —
     * crit / enchanted-hit tracking stars (the 3-tick window) and the
     * one-shot damage hearts. Source resolution rides the MEMBER identity
     * (emit.comp drops the spawn when the member is absent from this
     * client's pool); the light is the attacker's spawn-time sample riding
     * the broadcast. The ATTACKER skips this path — its own visuals spawned
     * instantly at click time (vanilla draws everyone's through the same
     * server relay; local-instant on the attacking client is the documented
     * deviation, matching its local attack sounds).
     */
    public void spawnCombatVisuals(int memberIdx, float light, boolean crit, boolean magic, int hearts) {
        if (crit) {
            int cid = particles.ensureCombatEmitter(CombatSpecs.CRIT);
            if (cid >= 0)
                this.trackings.add(new TrackingBurst(cid, 0, memberIdx, light));
        }
        if (magic) {
            int mid = particles.ensureCombatEmitter(CombatSpecs.MAGIC);
            if (mid >= 0)
                this.trackings.add(new TrackingBurst(mid, 0, memberIdx, light));
        }
        if (hearts > 0) {
            int hid = particles.ensureCombatEmitter(CombatSpecs.HEART);
            if (hid >= 0)
                this.combatBursts.add(new CombatBurst(hid, 0, memberIdx, light, hearts));
        }
    }

    // ---- crosshair hit-query snapshot (ordinary MODEL and server-owned Storm hit contract) ---
    /**
     * Newest fence-signalled hit-query snapshot: the packed sort key of the
     * allay under the crosshair ({@link ParticleBuffers#HIT_MISS} = none) and
     * its HP as float bits. At most one frame old -- a click consumes it with
     * zero stalls; a stale camera for one frame is accepted (documented).
     */
    private int hitKeySnapshot = ParticleBuffers.HIT_MISS;
    private int hitHpBits = 0;
    /**
     * Non-consuming mirror of {@link #hitKeySnapshot} for the crosshair pick
     * ({code ModelInteractionRuntime.injectCrosshairPick}): the click path
     * consume-once resets {@code hitKeySnapshot}, and the crosshair must not
     * flicker for the frame between a click and the next fence poll. Refreshed
     * at the same fence poll, cleared wherever the click snapshot is
     * invalidated.
     */
    private int crosshairHitKey = ParticleBuffers.HIT_MISS;
    /** Storm member identity of the newest hit-query snapshot (-1 = non-storm). */
    private int hitMemberIdx = -1;

    // ---- crosshair hit-query snapshot plumbing -------------------------------

    /**
     * Stores the fence-fresh hit-query readback (engine's
     * {@code pollCounterSnapshot}): packed key, winner HP bits, storm member
     * identity ({@code HIT_MISS} = non-storm MODEL winner).
     */
    private int hitToken;
    public int hitToken() { return this.hitToken; }
    public void onHitReadback(int key, int hpBits, int memberIdx, int token, ParticleHitRay ray, long generation) {
        lastHitRay=ray;lastHitGeneration=generation;
        this.hitToken = token;
        this.hitKeySnapshot = key;
        this.crosshairHitKey = key;
        this.hitHpBits = hpBits;
        this.hitMemberIdx = memberIdx == ParticleBuffers.HIT_MISS ? -1 : memberIdx;
    }

    /** Full hit-snapshot drop (pool reset): keys + HP + member identity. */
    public void dropHitSnapshots() {
        lastHitRay=null;lastHitGeneration=-1;
        this.hitToken=0;
        this.hitKeySnapshot = ParticleBuffers.HIT_MISS;
        this.crosshairHitKey = ParticleBuffers.HIT_MISS;
        this.hitHpBits = 0;
        this.hitMemberIdx = -1;
    }

    /**
     * Keys-only drop (storm kill frame / ACTIVATE / STOP): identities
     * reshuffle, but the HP and member slots are refreshed by the next fence
     * poll regardless.
     */
    public void dropHitKeys() {
        this.hitToken=0;
        this.hitKeySnapshot = ParticleBuffers.HIT_MISS;
        this.crosshairHitKey = ParticleBuffers.HIT_MISS;
    }

    /** Packed key of the newest hit snapshot (consume-once click path). */
    public int hitKeySnapshot() {
        return this.hitKeySnapshot;
    }

    /**
     * Consume-once click path: clears ONLY {@link #hitKeySnapshot} — the
     * crosshair mirror {@link #crosshairHitKey} stays so the crosshair does
     * not flicker in the frame between a click and the next fence poll.
     */
    public void consumeHitSnapshot() {
        this.hitKeySnapshot = ParticleBuffers.HIT_MISS;
    }

    /** Non-consuming mirror for the crosshair pick injection. */
    public int crosshairHitKey() {
        return this.crosshairHitKey;
    }

    /** Winner HP (float bits decoded) for the hurt/death sound choice. */
    public float hitHp() {
        return Float.intBitsToFloat(this.hitHpBits);
    }

    /** Storm member identity of the newest snapshot (-1 = non-storm winner). */
    public int hitMemberIdx() {
        return this.hitMemberIdx;
    }
    private ParticleHitRay lastHitRay;
    private final ModelParticlePort particles;
    public static final ModelInteractionRuntime INSTANCE = new ModelInteractionRuntime(ParticleRuntime.INSTANCE);
    public ModelInteractionRuntime(ModelParticlePort particles) { this.particles=java.util.Objects.requireNonNull(particles); }
    private static float sampleLight(ClientLevel level, Vec3 pos) {
        int packed=LevelRenderer.getLightColor(level,BlockPos.containing(pos));
        return LightTexture.block(packed)+16f*LightTexture.sky(packed);
    }
    public void reset() {
        dropHitSnapshots();dmgCount=0;dmgPending=false;dmgClearNext=false;
        trackings.clear();combatBursts.clear();proxyTarget=null;proxyLevel=null;vanillaPickResult=null;
        stagedTrackings.clear();stagedBursts=0;
    }
    public int pendingDamageCount() { return dmgPending?dmgCount:0; }
    /** Read-only upload view; acknowledging GPU work is a separate operation. */
    public ByteBuffer prepareDamage() {
        damageAccounting.measure(dmgCount);
        if(!dmgPending&&!dmgClearNext)return null;
        int count=pendingDamageCount();dmgTmp.putInt(0,count);
        var view=dmgTmp.asReadOnlyBuffer().order(dmgTmp.order());
        view.position(0).limit(ParticleBuffers.DAMAGE_HEADER_BYTES+count*ParticleBuffers.DAMAGE_ENTRY_BYTES);
        return view;
    }
    private final List<TrackingBurst> stagedTrackings=new ArrayList<>();
    private int stagedBursts;
    /** Preview visual inputs without advancing their local clocks until publication succeeds. */
    public void prepareVisuals(com.iridium126.createmanaindustry.client.particles.emitter.EmitCommandWriter writer,float dt) {
        stagedTrackings.clear();stagedBursts=0;
        for(var cb:combatBursts) {
            if(writer.full())break;
            writer.tracked(cb.emitId(),cb.count(),cb.localToken(),cb.memberIdx()>=0?cb.memberIdx()+1:-1,cb.light());
            stagedBursts++;
        }
        for(var old:trackings) {
            var t=new TrackingBurst(old.emitId,old.localToken,old.memberIdx,old.light);
            t.elapsed=old.elapsed+dt;t.remaining=old.remaining;
            double want=TrackingBurst.RATE*dt+old.acc;int n=(int)want;t.acc=want-n;
            n=Math.min(n,(int)Math.ceil(t.remaining));
            if(n>0&&!writer.full()) { writer.tracked(t.emitId,n,t.localToken,t.memberIdx>=0?t.memberIdx+1:-1,t.light);t.remaining-=n; }
            if(t.remaining>0&&t.elapsed<TrackingBurst.DURATION)stagedTrackings.add(t);
        }
    }
    public void commitInputs() {
        dmgClearNext=dmgPending;dmgPending=false;dmgCount=0;
        combatBursts.subList(0,stagedBursts).clear();stagedBursts=0;
        trackings.clear();trackings.addAll(stagedTrackings);stagedTrackings.clear();
    }

}
