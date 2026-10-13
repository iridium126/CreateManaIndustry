package com.iridium126.createmanaindustry.mixin.hextrick;

import com.iridium126.createmanaindustry.client.greattrick.*;
import dev.enjarai.trickster.render.CircleRenderer;
import dev.enjarai.trickster.screen.scribing.CircleWidget;
import dev.enjarai.trickster.spell.SpellView;
import io.wispforest.owo.braid.framework.proxy.WidgetState;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CircleWidget.class, remap = false)
public abstract class GreatCircleWidgetMixin implements CircleAccess {
    @Accessor("renderer") public abstract CircleRenderer cmi$renderer();
    @Accessor("partView") public abstract SpellView cmi$view();
    @Accessor("mutable") public abstract boolean cmi$mutable();
    @Accessor("hiddenByPonder") public abstract boolean cmi$hidden();
    @Accessor("hasInner") public abstract boolean cmi$inner();
    @Accessor("startingAngle") public abstract double cmi$angle();
    @Accessor("radius") public abstract double cmi$radius();
    @Accessor("highlight") public abstract boolean cmi$highlight();
    @Accessor("eval") public abstract Runnable cmi$eval();
    @Inject(method = "createState", at = @At("HEAD"), cancellable = true)
    private void cmi$great(CallbackInfoReturnable<WidgetState<?>> cir) {
        if (GreatTrickClient.greatMode()) cir.setReturnValue(new GreatCircleState());
    }
}
