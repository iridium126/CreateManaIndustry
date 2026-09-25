package com.iridium126.createmanaindustry.mixin.hexjit;

import at.petrak.hexcasting.api.casting.arithmetic.operator.Operator;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.ArithmeticCandidates;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "at.petrak.hexcasting.api.casting.arithmetic.engine.ArithmeticEngine$OpCandidates", remap = false)
public interface CandidatesMixin extends ArithmeticCandidates {
    @Override @Accessor("pattern") HexPattern cmi$pattern();
    @Override @Accessor("arity") int cmi$arity();
    @Override @Accessor("operators") List<Operator> cmi$operators();
}
