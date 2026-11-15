package com.iridium126.createmanaindustry.content.kinetics.temporarykinetics;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.iridium126.createmanaindustry.content.kinetics.temporarykinetics.TemporaryKineticsAccess.Effect;
import com.iridium126.createmanaindustry.mixin.kinetics.KineticBlockEntityAccessor;
import com.iridium126.createmanaindustry.mixin.kinetics.TorquePropagatorAccessor;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.IRotate.SpeedLevel;
import com.simibubi.create.content.kinetics.base.IRotate.StressImpact;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.utility.CreateLang;

import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class TemporaryKinetics {
    public static final String NBT_KEY = "CMITemporaryKinetics";
    public static final String SAVED_EFFECT_KEY = "CMITemporaryKineticsEffect";

    private TemporaryKinetics() {}

    public static void apply(KineticBlockEntity be, float stress, float speed, int durationTicks) {
        if (!(be.getLevel() instanceof ServerLevel level) || be.isRemoved() || durationTicks <= 0
                || !Float.isFinite(speed) || speed == 0 || !Float.isFinite(stress))
            return;

        var owner = owner(be);
        Effect stored = owner.createmanaindustry$getTemporaryEffect();
        long now = level.getGameTime();
        long deadline = now + durationTicks;
        stress = Math.abs(stress);
        boolean sameOutput = stored != null && stored.speed() == speed && stored.stress() == stress;
        if (sameOutput && stored.expiresAt() == deadline)
            return;

        Effect next = new Effect(speed, stress, deadline);
        owner.createmanaindustry$setTemporaryEffect(next);
        be.setChanged();

        if (isRestoring(be))
            return;
        TemporaryKineticsTicker.scheduleExpiry(level, be, stored == null ? 0 : stored.expiresAt(), next.expiresAt());
        if (sameOutput && stored.expiresAt() > now)
            return;

        if (stored != null && stored.expiresAt() > now && stored.speed() == speed) {
            KineticNetwork network = findNetwork(be);
            setContribution(network, be, next.stress());
            if (network != null)
                TemporaryKineticsNetwork.of(network).createmanaindustry$publishTemporaryCapacity();
        } else {
            updateRotation(be, speed, false, false);
        }
        if (!be.isRemoved())
            be.sendData();
    }

    /** Temporary capacity per RPM, retained for callers that use Create's coefficient units. */
    public static float getStress(KineticBlockEntity be) {
        Effect effect = activeEffect(be);
        return effect == null ? 0 : effect.stress() / Math.abs(effect.speed());
    }

    public static float getSpeed(KineticBlockEntity be) {
        Effect effect = activeEffect(be);
        return effect == null ? 0 : effect.speed();
    }

    public static boolean isActive(BlockEntity be) {
        return be instanceof KineticBlockEntity kinetic && activeEffect(kinetic) != null;
    }

    public static float networkContribution(KineticBlockEntity be) {
        Effect effect = activeEffect(be);
        return effect == null ? 0 : effect.stress();
    }

    @Nullable
    public static Effect readEffect(CompoundTag tag, boolean clientPacket) {
        String key = clientPacket ? NBT_KEY : SAVED_EFFECT_KEY;
        if (!tag.contains(key, Tag.TAG_COMPOUND))
            return null;

        CompoundTag saved = tag.getCompound(key);
        float speed = saved.getFloat("Speed");
        float stress = saved.getFloat("Stress");
        long expiry = clientPacket ? Long.MAX_VALUE : saved.getLong("ExpiresAt");
        if (speed == 0 || !Float.isFinite(speed) || stress < 0 || !Float.isFinite(stress) || expiry <= 0)
            return null;
        return new Effect(speed, stress, expiry);
    }

    public static void write(KineticBlockEntity be, CompoundTag tag, boolean clientPacket) {
        Effect effect = clientPacket ? activeEffect(be) : owner(be).createmanaindustry$getTemporaryEffect();
        if (effect != null) {
            CompoundTag saved = new CompoundTag();
            saved.putFloat("Speed", effect.speed());
            saved.putFloat("Stress", effect.stress());
            if (!clientPacket)
                saved.putLong("ExpiresAt", effect.expiresAt());
            tag.put(clientPacket ? NBT_KEY : SAVED_EFFECT_KEY, saved);
        }

        if (!clientPacket && tag.contains("Network", Tag.TAG_COMPOUND)) {
            KineticNetwork network = findNetwork(be);
            // Create can prune a member before the unloading chunk saves its block entities.
            float nativeCapacity = network != null && network.members.containsKey(be)
                    ? TemporaryKineticsNetwork.of(network).createmanaindustry$getNativeCapacity()
                    : owner(be).createmanaindustry$getNativeCapacitySnapshot();
            if (!Float.isNaN(nativeCapacity))
                tag.getCompound("Network").putFloat("Capacity", nativeCapacity);
        }
    }

    public static void readClient(KineticBlockEntity be, @Nullable Effect effect) {
        boolean wasActive = owner(be).createmanaindustry$getTemporaryEffect() != null;
        owner(be).createmanaindustry$setTemporaryEffect(effect);
        if (wasActive == (effect != null) || be.getLevel() == null || be.isRemoved())
            return;

        var manager = VisualizationManager.get(be.getLevel());
        if (manager != null) {
            var visuals = manager.blockEntities();
            visuals.queueRemove(be);
            visuals.queueAdd(be);
        }
    }

    public static void removed(KineticBlockEntity be, boolean chunkUnloaded) {
        Effect effect = owner(be).createmanaindustry$getTemporaryEffect();
        if (effect == null)
            return;

        if (be.getLevel() instanceof ServerLevel level) {
            TemporaryKineticsTicker.scheduleExpiry(level, be, effect.expiresAt(), 0);
            setContribution(findNetwork(be), be, 0);
            if (chunkUnloaded)
                return;
        }
        owner(be).createmanaindustry$setTemporaryEffect(null);
    }

    public static boolean isRestoring(KineticBlockEntity be) {
        return owner(be).createmanaindustry$isRestoringTemporaryEffect();
    }

    public static void finishRestoring(KineticBlockEntity be) {
        Effect effect = activeEffect(be);
        if (effect == null)
            expire(be, true, false);
        else {
            TemporaryKineticsTicker.scheduleExpiry((ServerLevel) be.getLevel(), be, 0, effect.expiresAt());
            updateRotation(be, effect.speed(), true, false);
            if (!be.isRemoved())
                be.sendData();
        }
    }

    public static void beforeTick(KineticBlockEntity be) {
        if (owner(be).createmanaindustry$getTemporaryEffect() != null
                && be.getLevel() instanceof ServerLevel && !isRestoring(be) && activeEffect(be) == null)
            expire(be, false, false);
    }

    public static void rememberLostSource(KineticBlockEntity be) {
        if (be.hasSource() && activeEffect(be) != null && be.getLevel() instanceof ServerLevel)
            owner(be).createmanaindustry$setTemporarySourcePending(true);
    }

    public static void reactivateSource(KineticBlockEntity be) {
        var owner = owner(be);
        if (!owner.createmanaindustry$isTemporarySourcePending())
            return;
        owner.createmanaindustry$setTemporarySourcePending(false);

        Effect effect = activeEffect(be);
        if (effect != null && (!followsStrongerSource(be, effect.speed())
                || Math.signum(be.getTheoreticalSpeed()) != Math.signum(effect.speed()))) {
            updateRotation(be, effect.speed(), false, false);
            if (!be.isRemoved())
                be.sendData();
        }
    }

    /** Returns true when this effect owns the generator's rotation update. */
    public static boolean onNativeGenerationChanged(KineticBlockEntity be) {
        if (owner(be).createmanaindustry$getTemporaryEffect() == null || !(be.getLevel() instanceof ServerLevel))
            return false;
        if (isRestoring(be))
            return true;

        Effect effect = activeEffect(be);
        if (effect == null) {
            expire(be, false, true);
        } else {
            KineticNetwork network = findNetwork(be);
            if (network != null && network.members.containsKey(be)) {
                refreshNativeSource(network, be);
                network.members.put(be, be.calculateStressApplied());
                network.updateNetwork();
            }
            be.setChanged();
            be.sendData();
        }
        return true;
    }

    public static boolean addToGoggleTooltip(KineticBlockEntity be, List<Component> tooltip) {
        Effect effect = activeEffect(be);
        if (effect == null || effect.stress() == 0 || !StressImpact.isEnabled())
            return false;

        CreateLang.translate("gui.goggles.generator_stats").forGoggles(tooltip);
        CreateLang.translate("tooltip.capacityProvided").style(ChatFormatting.GRAY).forGoggles(tooltip);
        CreateLang.number(effect.stress())
                .translate("generic.unit.stress")
                .style(ChatFormatting.AQUA)
                .forGoggles(tooltip, 1);
        return true;
    }

    private static TemporaryKineticsAccess owner(KineticBlockEntity be) {
        return (TemporaryKineticsAccess) be;
    }

    @Nullable
    private static Effect activeEffect(KineticBlockEntity be) {
        Effect effect = owner(be).createmanaindustry$getTemporaryEffect();
        return effect != null && be.getLevel() instanceof ServerLevel level && effect.expiresAt() <= level.getGameTime()
                ? null : effect;
    }

    @Nullable
    private static KineticNetwork findNetwork(KineticBlockEntity be) {
        if (!be.hasNetwork() || !(be.getLevel() instanceof ServerLevel level))
            return null;
        var networks = TorquePropagatorAccessor.createmanaindustry$getNetworks().get(level);
        return networks == null ? null : networks.get(be.network);
    }

    private static void setContribution(@Nullable KineticNetwork network, KineticBlockEntity be, float stress) {
        if (network != null)
            TemporaryKineticsNetwork.of(network).createmanaindustry$setTemporaryCapacity(be,
                    network.members.containsKey(be) ? stress : 0);
    }

    private static void expire(KineticBlockEntity be, boolean restoring, boolean nativeGenerationChanged) {
        Effect expired = owner(be).createmanaindustry$getTemporaryEffect();
        owner(be).createmanaindustry$setTemporaryEffect(null);
        owner(be).createmanaindustry$setTemporarySourcePending(false);
        TemporaryKineticsTicker.scheduleExpiry((ServerLevel) be.getLevel(), be, expired.expiresAt(), 0);
        updateRotation(be, be.getGeneratedSpeed(), restoring, nativeGenerationChanged);
        if (!be.isRemoved()) {
            be.setChanged();
            be.sendData();
        }
    }

    private static boolean followsStrongerSource(KineticBlockEntity be, float speed) {
        return be.hasSource() && Math.abs(be.getTheoreticalSpeed()) >= Math.abs(speed);
    }

    private static void updateRotation(KineticBlockEntity be, float speed, boolean restoring,
            boolean nativeGenerationChanged) {
        float previousSpeed = be.getTheoreticalSpeed();
        float previousEffectiveSpeed = be.getSpeed();
        KineticNetwork previous = findNetwork(be);
        float stress = networkContribution(be);
        setContribution(previous, be, stress);

        boolean propagated = applySpeed(be, previousSpeed, speed, restoring);
        KineticNetwork current = be.isRemoved() ? null : findNetwork(be);
        setContribution(current, be, stress);

        if (propagated || nativeGenerationChanged) {
            if (current != null && current.members.containsKey(be)) {
                if (nativeGenerationChanged)
                    refreshNativeSource(current, be);
                current.members.put(be, be.calculateStressApplied());
            }
            updateNetwork(previous);
            if (current != previous)
                updateNetwork(current);
        } else if (current != null) {
            TemporaryKineticsNetwork.of(current).createmanaindustry$publishTemporaryCapacity();
        }

        if (!be.isRemoved() && previousSpeed != be.getTheoreticalSpeed())
            be.onSpeedChanged(previousEffectiveSpeed);
    }

    private static void refreshNativeSource(KineticNetwork network, KineticBlockEntity be) {
        if (be.getGeneratedSpeed() == 0)
            network.sources.remove(be);
        else
            network.sources.put(be, be.calculateAddedStressCapacity());
    }

    private static boolean applySpeed(KineticBlockEntity be, float previousSpeed, float speed, boolean restoring) {
        if (speed != 0 && followsStrongerSource(be, speed)) {
            if (Math.signum(previousSpeed) != Math.signum(speed)) {
                be.getLevel().destroyBlock(be.getBlockPos(), true);
                return true;
            }
            if (restoring)
                be.attachKinetics();
            return restoring;
        }

        if (speed == 0 && be.hasSource()) {
            if (restoring)
                be.attachKinetics();
            return restoring;
        }

        if (previousSpeed == speed && (speed == 0 || be.hasNetwork())) {
            if (restoring)
                be.attachKinetics();
            return restoring;
        }

        if (!be.hasSource() && SpeedLevel.of(previousSpeed) != SpeedLevel.of(speed))
            ((KineticBlockEntityAccessor) be).createmanaindustry$getEffects().queueRotationIndicators();
        if (previousSpeed != 0)
            be.detachKinetics();

        if (speed == 0 || be.hasSource())
            be.removeSource();

        if (speed != 0) {
            be.setSpeed(speed);
            if (!be.hasNetwork()) {
                Long id = be instanceof GeneratingKineticBlockEntity generator
                        ? generator.createNetworkId() : be.getBlockPos().asLong();
                be.setNetwork(id);
            }
        }

        be.attachKinetics();
        owner(be).createmanaindustry$setTemporarySourcePending(false);
        return true;
    }

    private static void updateNetwork(@Nullable KineticNetwork network) {
        if (network != null && !network.members.isEmpty())
            network.updateNetwork();
    }
}
