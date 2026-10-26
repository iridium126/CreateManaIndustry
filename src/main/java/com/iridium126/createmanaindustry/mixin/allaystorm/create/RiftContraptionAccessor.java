package com.iridium126.createmanaindustry.mixin.allaystorm.create;

import com.simibubi.create.content.contraptions.Contraption;
import net.createmod.catnip.math.BlockFace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.Map;
import java.util.UUID;

@Mixin(Contraption.class)
public interface RiftContraptionAccessor {
    @Accessor("stabilizedSubContraptions") Map<UUID,BlockFace> cmi$children();
}
