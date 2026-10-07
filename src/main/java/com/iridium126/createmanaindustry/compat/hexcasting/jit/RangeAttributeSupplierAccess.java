package com.iridium126.createmanaindustry.compat.hexcasting.jit;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.attributes.*;

public interface RangeAttributeSupplierAccess {
    Map<Holder<Attribute>, AttributeInstance> cmi$getDefaultRangeAttributeInstances();
}
