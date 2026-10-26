package com.iridium126.createmanaindustry.mixin.allaystorm;
import com.iridium126.createmanaindustry.content.allaystorm.rift.RiftTransferCoordinator;
import net.minecraft.server.MinecraftServer;
import java.util.function.BooleanSupplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(MinecraftServer.class)
public abstract class RiftRecoveryBootMixin {
    @Inject(method="tickServer",at=@At("HEAD"),cancellable=true)
    private void cmi$journalFirst(BooleanSupplier time,CallbackInfo ci){if(!RiftTransferCoordinator.allowWorldTick())ci.cancel();}
}
