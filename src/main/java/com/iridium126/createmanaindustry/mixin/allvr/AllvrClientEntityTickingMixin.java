package com.iridium126.createmanaindustry.mixin.allvr;

import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits;
import com.iridium126.createmanaindustry.dimension.cube.*;
import com.iridium126.createmanaindustry.client.dimension.AllvrClientCubeCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class AllvrClientEntityTickingMixin implements AllvrEntityLevelAccess {
    @Shadow @Final private TransientEntitySectionManager<Entity> entityStorage;
    @Unique private long allvr$lastResidency = Long.MIN_VALUE;
    @Override public AllvrEntitySectionStorage<?> allvr$entitySections() { return ((AllvrEntityManagerAccess) entityStorage).allvr$sections(); }
    @Inject(method = "<init>", at = @At("RETURN"))
    private void allvr$enable(CallbackInfo ci) {
        ClientLevel self = (ClientLevel) (Object) this;
        if (AllvrDimensions.isAllay(self)) allvr$entitySections().enable(coord -> AllvrClientCubeCache.currentLevel() == self && AllvrClientCubeCache.isLoaded(coord.origin()) ? Visibility.TICKING : Visibility.TRACKED);
    }
    @Inject(method = "tickEntities", at = @At("HEAD"))
    private void allvr$sync(CallbackInfo ci) {
        if (!allvr$entitySections().enabled() || allvr$lastResidency == AllvrClientCubeCache.residencyRevision()) return;
        allvr$lastResidency = AllvrClientCubeCache.residencyRevision();
        ((AllvrTransientEntityManagerAccess) entityStorage).allvr$syncSections();
    }
}
