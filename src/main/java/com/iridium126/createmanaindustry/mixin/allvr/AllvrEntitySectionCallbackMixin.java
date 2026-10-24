package com.iridium126.createmanaindustry.mixin.allvr;

import com.iridium126.createmanaindustry.dimension.cube.*;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import net.minecraft.core.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.neoforged.neoforge.common.NeoForge;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = {"net.minecraft.world.level.entity.PersistentEntitySectionManager$Callback", "net.minecraft.world.level.entity.TransientEntitySectionManager$Callback"})
public abstract class AllvrEntitySectionCallbackMixin {
    @Shadow(remap = false) @Final private EntityAccess entity;
    @Unique private AllvrSectionCoord allvr$previous;
    @Unique private AllvrEntitySectionStorage<?> allvr$storage() {
        if (entity instanceof Entity real && real.level() instanceof AllvrEntityLevelAccess access) {
            var storage = access.allvr$entitySections();
            if (storage != null && storage.enabled()) return storage;
        }
        return null;
    }
    @Inject(method = "<init>", at = @At("RETURN"))
    private void allvr$initial(CallbackInfo ci) { allvr$previous = AllvrSectionCoord.of(entity.blockPosition()); }
    @WrapOperation(method = "onMove", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/SectionPos;asLong(Lnet/minecraft/core/BlockPos;)J"))
    private long allvr$key(BlockPos pos, Operation<Long> original) { var storage = allvr$storage(); return storage == null ? original.call(pos) : storage.key(pos); }
    @WrapOperation(method = {"onMove", "onRemove"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/core/SectionPos;of(J)Lnet/minecraft/core/SectionPos;"))
    private SectionPos allvr$logPosition(long key, Operation<SectionPos> original) {
        var storage = allvr$storage(); return storage == null ? original.call(key) : storage.coordinate(key).sectionPos();
    }
    @WrapOperation(method = "onMove", at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/common/CommonHooks;onEntityEnterSection(Lnet/minecraft/world/entity/Entity;JJ)V"))
    private void allvr$event(Entity real, long oldKey, long newKey, Operation<Void> original) {
        if (allvr$storage() == null) { original.call(real, oldKey, newKey); return; }
        var current = AllvrSectionCoord.of(real.blockPosition());
        NeoForge.EVENT_BUS.post(new AllvrEntityEnteringSectionEvent(real, allvr$previous, current));
        allvr$previous = current;
    }
}
