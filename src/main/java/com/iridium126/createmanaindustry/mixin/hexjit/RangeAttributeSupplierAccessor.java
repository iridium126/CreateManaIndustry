package com.iridium126.createmanaindustry.mixin.hexjit;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.RangeAttributeSupplierAccess;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.attributes.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AttributeSupplier.class)
public interface RangeAttributeSupplierAccessor extends RangeAttributeSupplierAccess {
    @Override @Accessor("instances") Map<Holder<Attribute>, AttributeInstance> cmi$getDefaultRangeAttributeInstances();
}
