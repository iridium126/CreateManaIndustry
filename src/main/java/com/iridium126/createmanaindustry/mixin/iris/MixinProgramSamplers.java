package com.iridium126.createmanaindustry.mixin.iris;

import java.util.HashSet;
import java.util.Set;

import com.iridium126.createmanaindustry.client.particles.shaderpack.ShaderPackProgramCompiler;
import com.iridium126.createmanaindustry.client.particles.shaderpack.PackageSamplerReservation;
import com.iridium126.createmanaindustry.CreateManaIndustry;

import net.irisshaders.iris.gl.program.ProgramSamplers;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Reserves the texture units the merged MODEL-particle programs pin their TBO
 * samplers to ({@code ShaderPackProgramCompiler#MERGED_SAMPLER_UNIT_BASE}
 * through {@code +3}) inside Iris's own sampler allocator, so a pack sampler
 * can never be assigned onto them -- the same practice iris-flw-compat uses
 * for its Flywheel units via this exact builder parameter. Without the
 * reservation, a pack sampler landing on units 10-13 would silently fight
 * the particle TBO fetches within the same draw.
 *
 * <p>Retains the existing four-unit reservation under iris-veil-compat. In an
 * Iris-only environment only package program construction reserves three units;
 * unrelated native programs retain the pack's entire sampler budget.
 *
 * <p>Gated on Iris presence by {@code CMIMixinPlugin}. Packages use the first
 * three units; MODEL uses all four. The two passes never run concurrently.
 */
@Mixin(value = ProgramSamplers.class, remap = false)
public class MixinProgramSamplers {

    @ModifyVariable(method = "builder", at = @At("LOAD"), argsOnly = true)
    private static Set<Integer> createmanaindustry$reserveParticleTboUnits(Set<Integer> reservedTextureUnits) {
        if (!CreateManaIndustry.IRISVEIL_ACTIVE && !PackageSamplerReservation.active())
            return reservedTextureUnits;
        // The set Iris hands in is immutable, so duplicate before modifying.
        Set<Integer> units = new HashSet<>(reservedTextureUnits);
        // MERGED_SAMPLER_UNIT_BASE is a compile-time constant, so referencing it
        // here neither loads the compiler class nor risks drift between the two.
        for (int i = 0; i < (CreateManaIndustry.IRISVEIL_ACTIVE ? 4 : 3); i++)
            units.add(ShaderPackProgramCompiler.MERGED_SAMPLER_UNIT_BASE + i);
        return units;
    }
}
