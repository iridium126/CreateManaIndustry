package com.iridium126.createmanaindustry.mixin.hextrick;

import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import com.iridium126.createmanaindustry.compat.hexcasting.HexCastChainState;
import com.iridium126.createmanaindustry.compat.trickster.casting.CastChains;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets="at.petrak.hexcasting.forge.xplat.ForgeXplatImpl",remap=false)
public abstract class StaffResetChainMixin {
    @Inject(method="setStaffcastImage",at=@At("HEAD"))
    private void cmi$reset(ServerPlayer player,CastingImage image,CallbackInfo ci) {
        var previous = HexCastChainState.savedId(player);
        var replacement = image == null ? null : HexCastChainState.get(image);
        if (previous != null && (replacement == null || !previous.equals(replacement.id())))
            CastChains.resetStaff(player, previous);
    }
    @Inject(method="clearCastingData",at=@At("HEAD"))
    private void cmi$clear(ServerPlayer player,CallbackInfo ci) {
        CastChains.resetStaff(player, HexCastChainState.savedId(player));
    }
}
