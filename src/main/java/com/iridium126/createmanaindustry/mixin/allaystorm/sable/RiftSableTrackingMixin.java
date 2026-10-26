package com.iridium126.createmanaindustry.mixin.allaystorm.sable;
import com.iridium126.createmanaindustry.content.allaystorm.rift.StormRiftTransfers;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.system.SubLevelTrackingSystem;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.player.Player;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(SubLevelTrackingSystem.class)
public abstract class RiftSableTrackingMixin {
    @Shadow @Final private ServerLevel level;
    @Inject(method="shouldLoad",at=@At("HEAD"),cancellable=true)
    private void cmi$group(Player player,Vector3dc position,CallbackInfoReturnable<Boolean> ci){for(var sub:SubLevelContainer.getContainer(level).getAllSubLevels())if(sub.logicalPose().position()==position){if(StormRiftTransfers.staging(level,sub.getUniqueId()))ci.setReturnValue(false);else if(StormRiftTransfers.together(sub.getUniqueId(),player.getUUID()))ci.setReturnValue(true);return;}}
}
