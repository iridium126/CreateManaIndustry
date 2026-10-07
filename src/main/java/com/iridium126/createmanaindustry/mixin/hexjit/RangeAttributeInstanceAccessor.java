package com.iridium126.createmanaindustry.mixin.hexjit;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.RangeAttributeInstanceAccess;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AttributeInstance.class)
public interface RangeAttributeInstanceAccessor extends RangeAttributeInstanceAccess {
    @Override @Accessor("dirty") boolean cmi$isRangeAttributeDirty();
}
