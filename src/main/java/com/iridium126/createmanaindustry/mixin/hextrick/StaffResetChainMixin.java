package com.iridium126.createmanaindustry.mixin.hextrick;

import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import com.iridium126.createmanaindustry.compat.trickster.CastChain;
import com.iridium126.createmanaindustry.compat.trickster.CastChains;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets="at.petrak.hexcasting.forge.xplat.ForgeXplatImpl",remap=false)
public abstract class StaffResetChainMixin {
    @Shadow @Final public static String TAG_VM;
    @Inject(method="setStaffcastImage",at=@At("HEAD"))
    private void cmi$reset(ServerPlayer player,CastingImage image,CallbackInfo ci) {
        String previous=player.getPersistentData().getCompound(TAG_VM).getCompound("userData").getString("cmi_cast_chain");
        if(previous.isEmpty())return;
        String replacement=image==null?"":image.getUserData().getString("cmi_cast_chain");
        if(replacement.isEmpty()||!CastChain.decode(previous).id().equals(CastChain.decode(replacement).id()))
            CastChains.resetStaff(player,previous);
    }
    @Inject(method="clearCastingData",at=@At("HEAD"))
    private void cmi$clear(ServerPlayer player,CallbackInfo ci) {
        CastChains.resetStaff(player,player.getPersistentData().getCompound(TAG_VM).getCompound("userData").getString("cmi_cast_chain"));
    }
}
