package com.iridium126.createmanaindustry.compat.hexcasting.jit;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.attributes.*;

/** Read-only access; instance lookup never materializes a player attribute. */
public interface RangeAttributeMapAccess {
    Map<Holder<Attribute>, AttributeInstance> cmi$getRangeAttributeInstances();
    AttributeSupplier cmi$getRangeAttributeSupplier();
}
