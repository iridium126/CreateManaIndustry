package com.iridium126.createmanaindustry.mixin.allaystorm;
import net.minecraft.server.level.ChunkMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(ChunkMap.class)
public interface RiftTrackingAccessor {@Accessor("entityMap") Int2ObjectMap<?> cmi$tracked();}
