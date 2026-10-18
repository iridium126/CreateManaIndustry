package com.iridium126.createmanaindustry.mixin.hextrick;

import at.petrak.hexcasting.api.casting.circles.BlockEntityAbstractImpetus;
import at.petrak.hexcasting.common.blocks.circles.BlockEntitySlate;
import com.iridium126.createmanaindustry.compat.hextrick.execution.SourceIdentity;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = {BlockEntitySlate.class, BlockEntityAbstractImpetus.class}, remap = false)
public abstract class CircleSourceIdentityMixin implements SourceIdentity {
    @Unique private UUID cmi$identity;

    @Override
    public UUID cmi$sourceIdentity() {
        if (cmi$identity == null) {
            cmi$identity = UUID.randomUUID();
            ((BlockEntity) (Object) this).setChanged();
        }
        return cmi$identity;
    }

    @Override
    public void cmi$newSourceIdentity() {
        cmi$identity = UUID.randomUUID();
        ((BlockEntity) (Object) this).setChanged();
    }

    @Inject(method = "saveModData", at = @At("TAIL"))
    private void cmi$save(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo callback) {
        tag.putUUID("cmi_source_identity", cmi$sourceIdentity());
    }

    @Inject(method = "loadModData", at = @At("TAIL"))
    private void cmi$load(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo callback) {
        if (tag.hasUUID("cmi_source_identity")) cmi$identity = tag.getUUID("cmi_source_identity");
    }
}
