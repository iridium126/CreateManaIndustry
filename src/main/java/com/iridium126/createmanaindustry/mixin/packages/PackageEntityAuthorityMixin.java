package com.iridium126.createmanaindustry.mixin.packages;

import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageAuthorityManager;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import com.simibubi.create.content.logistics.box.PackageEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Only server-confirmed leases pause travel; Create retains lifecycle and every gameplay callback. */
@Mixin(PackageEntity.class)
public abstract class PackageEntityAuthorityMixin {
    @Inject(method="travel",at=@At("HEAD"),cancellable=true)
    private void cmi$travel(Vec3 input,CallbackInfo ci) {
        if(ServerConfig.packageGpuAuthority && PackageAuthorityManager.paused((PackageEntity)(Object)this))ci.cancel();
    }
    @Inject(method="tick",at=@At("RETURN"))
    private void cmi$keepCheckpoint(CallbackInfo ci) {
        if(ServerConfig.packageGpuAuthority)PackageAuthorityManager.maintainCheckpoint((PackageEntity)(Object)this);
    }
    @Inject(method="decreaseInsertionTimer",at=@At("HEAD"))
    private void cmi$machineHandoff(Vec3 target,CallbackInfoReturnable<Boolean> cir){cmi$release();}
    @Inject(method="interact",at=@At("HEAD"))
    private void cmi$interaction(Player player,InteractionHand hand,CallbackInfoReturnable<InteractionResult> cir){cmi$release();}
    @Inject(method="hurt",at=@At("HEAD"))
    private void cmi$damage(DamageSource source,float amount,CallbackInfoReturnable<Boolean> cir){cmi$release();}
    @Inject(method="push",at=@At("HEAD"))
    private void cmi$externalPush(Entity other,CallbackInfo ci){cmi$release();}
    @Inject(method="setBox",at=@At("HEAD"))
    private void cmi$contentsChanged(ItemStack box,CallbackInfo ci){cmi$release();}
    @Inject(method="addAdditionalSaveData",at=@At("HEAD"))
    private void cmi$save(CompoundTag tag,CallbackInfo ci){cmi$release();}
    @Unique private void cmi$release(){PackageAuthorityManager.release((PackageEntity)(Object)this);}
}
