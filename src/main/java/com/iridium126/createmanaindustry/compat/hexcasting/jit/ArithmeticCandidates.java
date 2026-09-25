package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import at.petrak.hexcasting.api.casting.arithmetic.operator.Operator;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import java.util.List;

/** View of the original candidates, with their original registration order. */
public interface ArithmeticCandidates {
    HexPattern cmi$pattern();
    int cmi$arity();
    List<Operator> cmi$operators();
}
