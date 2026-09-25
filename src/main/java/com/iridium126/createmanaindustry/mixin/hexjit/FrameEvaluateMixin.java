package com.iridium126.createmanaindustry.mixin.hexjit;

import at.petrak.hexcasting.api.casting.eval.vm.FrameEvaluate;
import org.spongepowered.asm.mixin.Mixin;

/** Compatibility probe only: the original continuation and all serialization stay intact. */
@Mixin(value = FrameEvaluate.class, remap = false)
public abstract class FrameEvaluateMixin {}
