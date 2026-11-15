package com.iridium126.createmanaindustry.mixin.kinetics;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createmanaindustry.content.kinetics.temporarykinetics.TemporaryKinetics;
import com.iridium126.createmanaindustry.content.kinetics.temporarykinetics.TemporaryKineticsAccess;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

@Mixin(value = KineticBlockEntity.class, remap = false)
public abstract class KineticBlockEntityMixin implements TemporaryKineticsAccess {
    @Shadow protected boolean wasMoved;
    @Shadow protected float capacity;

    @Unique private volatile Effect createmanaindustry$temporaryEffect;
    @Unique private boolean createmanaindustry$restoringTemporaryEffect;
    @Unique private boolean createmanaindustry$temporarySourcePending;
    @Unique private float createmanaindustry$nativeCapacitySnapshot = Float.NaN;

    @Override
    public Effect createmanaindustry$getTemporaryEffect() {
        return createmanaindustry$temporaryEffect;
    }

    @Override
    public void createmanaindustry$setTemporaryEffect(Effect effect) {
        createmanaindustry$temporaryEffect = effect;
    }

    @Override
    public boolean createmanaindustry$isRestoringTemporaryEffect() {
        return createmanaindustry$restoringTemporaryEffect;
    }

    @Override
    public boolean createmanaindustry$isTemporarySourcePending() {
        return createmanaindustry$temporarySourcePending;
    }

    @Override
    public void createmanaindustry$setTemporarySourcePending(boolean pending) {
        createmanaindustry$temporarySourcePending = pending;
    }

    @Override
    public float createmanaindustry$getNativeCapacitySnapshot() {
        return createmanaindustry$nativeCapacitySnapshot;
    }

    @Override
    public void createmanaindustry$setNativeCapacitySnapshot(float capacity) {
        createmanaindustry$nativeCapacitySnapshot = capacity;
    }

    @Inject(method = "updateFromNetwork", at = @At("HEAD"))
    private void createmanaindustry$recordDirectNetworkUpdate(float capacity, float stress, int size, CallbackInfo ci) {
        createmanaindustry$nativeCapacitySnapshot = capacity;
    }

    @ModifyReturnValue(method = "isSource", at = @At("RETURN"))
    private boolean createmanaindustry$recognizeTemporarySource(boolean nativeSource) {
        return nativeSource || TemporaryKinetics.isActive((KineticBlockEntity) (Object) this);
    }

    @WrapOperation(method = "validateKinetics", at = @At(value = "INVOKE",
            target = "Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;getGeneratedSpeed()F"))
    private float createmanaindustry$validateEffectiveSpeed(KineticBlockEntity be, Operation<Float> original) {
        float temporary = TemporaryKinetics.getSpeed(be);
        return temporary == 0 ? original.call(be) : temporary;
    }

    @Inject(method = "removeSource", at = @At("HEAD"))
    private void createmanaindustry$rememberTemporarySource(CallbackInfo ci) {
        TemporaryKinetics.rememberLostSource((KineticBlockEntity) (Object) this);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void createmanaindustry$expireTemporarySource(CallbackInfo ci) {
        if (createmanaindustry$temporaryEffect != null)
            TemporaryKinetics.beforeTick((KineticBlockEntity) (Object) this);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void createmanaindustry$reactivateTemporarySource(CallbackInfo ci) {
        if (createmanaindustry$temporarySourcePending)
            TemporaryKinetics.reactivateSource((KineticBlockEntity) (Object) this);
    }

    @Inject(method = "attachKinetics", at = @At("HEAD"), cancellable = true)
    private void createmanaindustry$waitForRestoration(CallbackInfo ci) {
        if (createmanaindustry$restoringTemporaryEffect)
            ci.cancel();
    }

    @Inject(method = "initialize", at = @At(value = "INVOKE",
            target = "Lcom/simibubi/create/foundation/blockEntity/SmartBlockEntity;initialize()V"))
    private void createmanaindustry$restoreAfterNetworkRegistration(CallbackInfo ci) {
        if (!createmanaindustry$restoringTemporaryEffect)
            return;

        createmanaindustry$restoringTemporaryEffect = false;
        KineticBlockEntity be = (KineticBlockEntity) (Object) this;
        if (be.getLevel() instanceof ServerLevel)
            TemporaryKinetics.finishRestoring(be);
    }

    @Inject(method = "write", at = @At("RETURN"))
    private void createmanaindustry$writeTemporaryKinetics(CompoundTag tag, HolderLookup.Provider registries,
            boolean clientPacket, CallbackInfo ci) {
        TemporaryKinetics.write((KineticBlockEntity) (Object) this, tag, clientPacket);
    }

    @Inject(method = "read", at = @At("RETURN"))
    private void createmanaindustry$readTemporaryKinetics(CompoundTag tag, HolderLookup.Provider registries,
            boolean clientPacket, CallbackInfo ci) {
        if (clientPacket) {
            var effect = TemporaryKinetics.readEffect(tag, true);
            if (effect != null || createmanaindustry$temporaryEffect != null)
                TemporaryKinetics.readClient((KineticBlockEntity) (Object) this, effect);
        } else {
            createmanaindustry$temporaryEffect = wasMoved ? null : TemporaryKinetics.readEffect(tag, false);
            createmanaindustry$restoringTemporaryEffect = createmanaindustry$temporaryEffect != null;
            createmanaindustry$temporarySourcePending = false;
            createmanaindustry$nativeCapacitySnapshot = capacity;
        }
    }
}
