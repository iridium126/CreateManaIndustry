package com.iridium126.createmanaindustry.mixin.hextrick;

import at.petrak.hexcasting.common.items.ItemStaff;
import com.iridium126.createmanaindustry.compat.trickster.greattrick.GreatMode;
import dev.enjarai.trickster.item.EvaluationMirrorItem;
import dev.enjarai.trickster.screen.ScrollAndQuillScreenHandler;
import dev.enjarai.trickster.screen.ScrollAndQuillScreenHandler.InitialData;
import dev.enjarai.trickster.spell.*;
import io.vavr.collection.HashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ScrollAndQuillScreenHandler.class, remap = false)
public abstract class MirrorMenuMixin {
    @Inject(method = "factory", at = @At("HEAD"))
    private static void cmi$great(Component title, InitialData data, ItemStack main, ItemStack other,
            HashMap<Pattern, SpellPart> macros, CallbackInfoReturnable<ExtendedScreenHandlerFactory<InitialData>> cir) {
        ((GreatMode)(Object)data).cmi$greatMode(data.hand() == InteractionHand.MAIN_HAND
                && main.getItem() instanceof EvaluationMirrorItem && other.getItem() instanceof ItemStaff);
    }
}
