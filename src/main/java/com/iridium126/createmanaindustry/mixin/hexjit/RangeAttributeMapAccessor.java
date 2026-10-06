package com.iridium126.createmanaindustry.mixin.hexjit;
import com.iridium126.createmanaindustry.compat.hexcasting.jit.RangeAttributeMapAccess;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.attributes.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AttributeMap.class)
public interface RangeAttributeMapAccessor extends RangeAttributeMapAccess {
    @Override @Accessor("attributes") Map<Holder<Attribute>, AttributeInstance> cmi$getRangeAttributeInstances();
    @Override @Accessor("supplier") AttributeSupplier cmi$getRangeAttributeSupplier();
}
