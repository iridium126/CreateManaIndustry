package com.iridium126.createmanaindustry.mixin.allaystorm;

import com.iridium126.createmanaindustry.client.allaystorm.rift.AllayClientTime;
import com.iridium126.createmanaindustry.content.allaystorm.rift.AllaySkyContext;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.GameRules;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class AllayClientTimeMixin implements AllayClientTime {
    @Shadow @Final private ClientLevel.ClientLevelData clientLevelData;
    @Inject(method="setDayTime",at=@At("HEAD"),cancellable=true)
    private void cmi$signedTick(long time,CallbackInfo ci) {
        if(AllvrDimensions.isAllay((ClientLevel)(Object)this)) { clientLevelData.setDayTime(time);ci.cancel(); }
    }
    @Override public void cmi$skyTime(AllaySkyContext context) {
        var level=(ClientLevel)(Object)this;
        clientLevelData.setDayTime(context.dayTime());
        level.setDayTimeFraction(context.fraction());level.setDayTimePerTick(context.perTick());
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(context.daylightCycle(),null);
    }
}
