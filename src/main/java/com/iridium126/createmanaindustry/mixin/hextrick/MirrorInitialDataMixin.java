package com.iridium126.createmanaindustry.mixin.hextrick;

import com.iridium126.createmanaindustry.compat.trickster.GreatMode;
import dev.enjarai.trickster.screen.ScrollAndQuillScreenHandler.InitialData;
import io.wispforest.endec.*;
import io.wispforest.endec.impl.StructEndecBuilder;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = InitialData.class, remap = false)
public abstract class MirrorInitialDataMixin implements GreatMode {
    @Shadow @Final @Mutable public static Endec<InitialData> ENDEC;
    @Unique private boolean cmi$great;
    public boolean cmi$greatMode() { return cmi$great; }
    public void cmi$greatMode(boolean mode) { cmi$great = mode; }
    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void cmi$syncMode(CallbackInfo ci) {
        ENDEC = StructEndecBuilder.of(((StructEndec<InitialData>)ENDEC).flatFieldOf(data -> data),
                Endec.BOOLEAN.optionalFieldOf("cmi_great_mode", data -> ((GreatMode)(Object)data).cmi$greatMode(), false),
                (data, great) -> { ((GreatMode)(Object)data).cmi$greatMode(great); return data; });
    }
}
