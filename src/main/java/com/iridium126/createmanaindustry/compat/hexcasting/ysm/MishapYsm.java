package com.iridium126.createmanaindustry.compat.hexcasting.ysm;

import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.mishaps.Mishap;
import at.petrak.hexcasting.api.pigment.FrozenPigment;
import at.petrak.hexcasting.api.utils.TreeList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import com.iridium126.createmanaindustry.compat.ysm.YsmChatMessages;

public final class MishapYsm extends Mishap {
    private final String reason;
    public MishapYsm(String reason) { this.reason = reason; }
    @Override public FrozenPigment accentColor(CastingEnvironment env, Context context) { return dyeColor(DyeColor.LIGHT_BLUE); }
    @Override public TreeList<Iota> execute(CastingEnvironment env, Context context, TreeList<Iota> stack) { return stack; }
    @Override protected Component errorMessage(CastingEnvironment env, Context context) {
        return YsmChatMessages.actionFailed(reason);
    }
}
