package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import at.petrak.hexcasting.api.casting.eval.sideeffects.EvalSound;
import at.petrak.hexcasting.api.casting.math.HexPattern;

/** Verified, allocation-free components of the standard successful Staff Tick callback. */
public interface TickPostExecutionAccess {
    net.minecraft.server.level.ServerPlayer cmi$getTickCaster();
    void cmi$refreshTickRangeAttributes();
    void cmi$recordTickPattern(HexPattern pattern);
    void cmi$postSuccessfulTick(HexPattern pattern, EvalSound sound);
    boolean cmi$hasPureRangeAttributes();
    boolean cmi$canCollapseQuotedCallbacks(EvalSound sound);
    void cmi$recordCollapsedQuotedCallbacks(EvalSound sound, int count);
}
