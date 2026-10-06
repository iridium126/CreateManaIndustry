package com.iridium126.createmanaindustry.mixin.hexjit;

import at.petrak.hexcasting.api.utils.TreeList;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.ExecutionScope;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Records the latest persistent-list append parent for an identity-checked Tick pop. */
@Mixin(targets = {
        "at.petrak.hexcasting.api.utils.TreeList$TreeList1",
        "at.petrak.hexcasting.api.utils.TreeList$TreeList2",
        "at.petrak.hexcasting.api.utils.TreeList$TreeList3",
        "at.petrak.hexcasting.api.utils.TreeList$TreeList4",
        "at.petrak.hexcasting.api.utils.TreeList$TreeList5",
        "at.petrak.hexcasting.api.utils.TreeList$TreeList6"
}, remap = false)
public abstract class TreeListAppendCacheMixin {
    @Inject(method = "appended(Ljava/lang/Object;)Lat/petrak/hexcasting/api/utils/TreeList;",
            at = @At("RETURN"))
    private void cmi$rememberTickStackAppend(Object element, CallbackInfoReturnable<TreeList> callback) {
        if (!ServerConfig.hexJitCacheTickStackPop) return;
        ExecutionScope.recordTickStackAppendOnServerThread(
                (TreeList<?>) (Object) this, callback.getReturnValue());
    }
}
