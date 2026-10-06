package com.iridium126.createmanaindustry.mixin.hexjit;

import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.utils.TreeList;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.CastingImageLoopAccess;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Lets the closed, observer-free Tick loop reuse its current immutable image object. No regular
 * HexCasting path calls this bridge; eligibility is gated by the loop-batch config and runtime
 * shape checks, and the mixin is applied only after the upstream fields are verified.
 */
@Mixin(CastingImage.class)
public abstract class CastingImageLoopMixin implements CastingImageLoopAccess {
    @Shadow @Final @Mutable private TreeList<Iota> stack;
    @Shadow @Final @Mutable private long opsConsumed;
    @Shadow @Final @Mutable private CompoundTag userData;

    @Override
    @Unique
    public void cmi$applyFastTickOutput(TreeList<Iota> stack, long opsConsumed, CompoundTag userData) {
        this.stack = stack;
        this.opsConsumed = opsConsumed;
        this.userData = userData;
    }
}
