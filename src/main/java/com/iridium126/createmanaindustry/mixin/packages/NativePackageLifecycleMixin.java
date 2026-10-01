package com.iridium126.createmanaindustry.mixin.packages;

import com.iridium126.createmanaindustry.client.particles.packages.PackageNativeObserverClient;
import com.simibubi.create.content.logistics.box.PackageEntity;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Additional spawn data and logistics handoff are not ordinary entity pose packets. */
@Mixin(PackageEntity.class)
public abstract class NativePackageLifecycleMixin {
    @Unique private boolean cmi$readingSpawn;
    @Inject(method="readSpawnData",at=@At("HEAD"),remap=false)
    private void cmi$beginSpawn(RegistryFriendlyByteBuf data,CallbackInfo ci){cmi$readingSpawn=true;}
    @Inject(method="readSpawnData",at=@At("RETURN"),remap=false)
    private void cmi$spawn(RegistryFriendlyByteBuf data,CallbackInfo ci){cmi$readingSpawn=false;PackageNativeObserverClient.entityAvailable((PackageEntity)(Object)this);}
    @Inject(method="setBox",at=@At("HEAD"),remap=false)
    private void cmi$contents(ItemStack box,CallbackInfo ci){PackageNativeObserverClient.changed((PackageEntity)(Object)this);}
    @Inject(method="setBox",at=@At("RETURN"),remap=false)
    private void cmi$modelReady(ItemStack box,CallbackInfo ci){if(!cmi$readingSpawn)PackageNativeObserverClient.entityAvailable((PackageEntity)(Object)this);}
    @Inject(method="decreaseInsertionTimer",at=@At("HEAD"),remap=false)
    private void cmi$machine(Vec3 target,CallbackInfoReturnable<Boolean> cir){PackageNativeObserverClient.changed((PackageEntity)(Object)this);}
}
