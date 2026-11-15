package com.iridium126.createmanaindustry.mixin.kinetics;

import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createmanaindustry.content.kinetics.temporarykinetics.TemporaryKinetics;
import com.iridium126.createmanaindustry.content.kinetics.temporarykinetics.TemporaryKineticsAccess;
import com.iridium126.createmanaindustry.content.kinetics.temporarykinetics.TemporaryKineticsNetwork;
import com.iridium126.createmanaindustry.content.kinetics.temporarykinetics.TemporaryKineticsTicker;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import it.unimi.dsi.fastutil.objects.Reference2FloatOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

@Mixin(value = KineticNetwork.class, remap = false)
public abstract class KineticNetworkMixin implements TemporaryKineticsNetwork {
    @Shadow public Map<KineticBlockEntity, Float> sources;
    @Shadow public Map<KineticBlockEntity, Float> members;
    @Shadow private float currentCapacity;
    @Shadow public abstract void sync();

    @Unique private Reference2FloatOpenHashMap<KineticBlockEntity> createmanaindustry$temporarySources;
    @Unique private double createmanaindustry$temporaryCapacity;
    @Unique private float createmanaindustry$lastNativeCapacity;
    @Unique private float createmanaindustry$publishedNativeCapacity;
    @Unique private boolean createmanaindustry$countingNativeCapacity;
    @Unique private boolean createmanaindustry$publishingOnlyTemporaryCapacity;

    @WrapOperation(method = {"add", "addSilently", "remove"}, at = @At(value = "INVOKE",
            target = "Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;isSource()Z"))
    private boolean createmanaindustry$isNativeSource(KineticBlockEntity be, Operation<Boolean> original) {
        // The vanilla table persists its own per-source coefficients and speed multiplier.
        return TemporaryKinetics.isActive(be) ? be.getGeneratedSpeed() != 0 : original.call(be);
    }

    @Inject(method = "add", at = @At("RETURN"))
    private void createmanaindustry$afterAddingMember(KineticBlockEntity be, CallbackInfo ci) {
        createmanaindustry$registerTemporarySource(be);
    }

    @Inject(method = "addSilently", at = @At("RETURN"))
    private void createmanaindustry$afterRestoringMember(KineticBlockEntity be, float lastCapacity, float lastStress,
            CallbackInfo ci) {
        createmanaindustry$registerTemporarySource(be);
    }

    @Unique
    private void createmanaindustry$registerTemporarySource(KineticBlockEntity be) {
        float contribution = TemporaryKinetics.networkContribution(be);
        if (contribution != 0 && members.containsKey(be))
            createmanaindustry$setTemporaryCapacity(be, contribution);
    }

    @Inject(method = "remove", at = @At("HEAD"))
    private void createmanaindustry$unregisterTemporarySource(KineticBlockEntity be, CallbackInfo ci) {
        createmanaindustry$setTemporaryCapacity(be, 0);
    }

    @Override
    public void createmanaindustry$setTemporaryCapacity(KineticBlockEntity be, float stress) {
        if (createmanaindustry$temporarySources == null) {
            if (stress == 0)
                return;
            createmanaindustry$temporarySources = new Reference2FloatOpenHashMap<>();
        }
        float previous = stress == 0 ? createmanaindustry$temporarySources.removeFloat(be)
                : createmanaindustry$temporarySources.put(be, stress);
        if (previous == stress)
            return;

        createmanaindustry$temporaryCapacity = createmanaindustry$temporarySources.isEmpty() ? 0
                : createmanaindustry$temporaryCapacity + (double) stress - previous;
        if (be.getLevel() instanceof ServerLevel level)
            TemporaryKineticsTicker.queue(level, (KineticNetwork) (Object) this);
    }

    @WrapMethod(method = "calculateCapacity")
    private float createmanaindustry$includeTemporaryCapacity(Operation<Float> original) {
        // The native scan and the stored subtotal use vanilla's per-source multiplication.
        boolean countingNativeCapacity = createmanaindustry$countingNativeCapacity;
        createmanaindustry$countingNativeCapacity = true;
        try {
            createmanaindustry$lastNativeCapacity = original.call();
            return (float) (createmanaindustry$lastNativeCapacity + createmanaindustry$temporaryCapacity);
        } finally {
            createmanaindustry$countingNativeCapacity = countingNativeCapacity;
        }
    }

    @WrapMethod(method = "getActualCapacityOf")
    private float createmanaindustry$includeTemporaryPerSourceCapacity(KineticBlockEntity be, Operation<Float> original) {
        if (createmanaindustry$countingNativeCapacity)
            return original.call(be);

        float temporary = createmanaindustry$temporarySources == null
                ? 0 : createmanaindustry$temporarySources.getFloat(be);
        if (sources.containsKey(be) || (temporary == 0 && !TemporaryKinetics.isActive(be)))
            return original.call(be) + temporary;
        return temporary;
    }

    @WrapOperation(method = {"updateCapacity", "updateNetwork"}, at = @At(value = "INVOKE",
            target = "Lcom/simibubi/create/content/kinetics/KineticNetwork;calculateCapacity()F"))
    private float createmanaindustry$recordPublishedNativeCapacity(KineticNetwork network, Operation<Float> original) {
        float capacity = original.call(network);
        createmanaindustry$publishedNativeCapacity = createmanaindustry$lastNativeCapacity;
        return capacity;
    }

    @Override
    public float createmanaindustry$getNativeCapacity() {
        return createmanaindustry$publishedNativeCapacity;
    }

    @Override
    public void createmanaindustry$publishTemporaryCapacity() {
        float capacity = (float) (createmanaindustry$publishedNativeCapacity + createmanaindustry$temporaryCapacity);
        if (capacity != currentCapacity) {
            currentCapacity = capacity;
            boolean temporaryOnly = createmanaindustry$publishingOnlyTemporaryCapacity;
            createmanaindustry$publishingOnlyTemporaryCapacity = true;
            try {
                sync();
            } finally {
                createmanaindustry$publishingOnlyTemporaryCapacity = temporaryOnly;
            }
        }
    }

    @WrapOperation(method = "updateFromNetwork", at = @At(value = "INVOKE",
            target = "Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;updateFromNetwork(FFI)V"))
    private void createmanaindustry$skipRemovedMember(KineticBlockEntity be, float capacity, float stress, int size,
            Operation<Void> original) {
        if (!be.isRemoved()) {
            boolean dirty = createmanaindustry$publishingOnlyTemporaryCapacity && be.networkDirty;
            original.call(be, capacity, stress, size);
            ((TemporaryKineticsAccess) be).createmanaindustry$setNativeCapacitySnapshot(
                    createmanaindustry$publishedNativeCapacity);
            if (dirty)
                be.networkDirty = true;
        }
    }

    @WrapOperation(method = {"calculateCapacity", "calculateStress"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;",
            remap = true))
    private BlockEntity createmanaindustry$findLoadedNetworkMember(Level level, BlockPos pos,
            Operation<BlockEntity> original) {
        return level.isLoaded(pos) ? original.call(level, pos) : null;
    }
}
