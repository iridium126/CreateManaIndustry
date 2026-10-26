package com.iridium126.createmanaindustry.mixin.allaystorm.sable;
import com.iridium126.createmanaindustry.content.allaystorm.rift.sable.RiftSableFreeze;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
/** Empty target plots are staged across frames and must not be auto-destroyed before their snapshot is loaded. */
@Mixin(SubLevelContainer.class)
public abstract class RiftSubLevelIntegrityMixin {
    @WrapOperation(method="processSubLevelRemovals",at=@At(value="INVOKE",target="Ldev/ryanhcode/sable/api/physics/mass/MassData;isInvalid()Z"))
    private boolean cmi$stagedMass(MassData mass,Operation<Boolean> original,@Local ServerSubLevel sub){return !RiftSableFreeze.frozen(sub)&&original.call(mass);}
}
