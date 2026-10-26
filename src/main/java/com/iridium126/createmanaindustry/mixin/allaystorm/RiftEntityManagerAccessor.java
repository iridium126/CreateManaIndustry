package com.iridium126.createmanaindustry.mixin.allaystorm;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.*;
import java.util.function.Consumer;
@Mixin(PersistentEntitySectionManager.class)
public interface RiftEntityManagerAccessor {
    @Accessor("permanentStorage") EntityPersistentStorage<Entity> cmi$storage();
    @Accessor("sectionStorage") EntitySectionStorage<Entity> cmi$sections();
    @Accessor("chunkLoadStatuses") it.unimi.dsi.fastutil.longs.Long2ObjectMap<?> cmi$loadStates();
    @Invoker("requestChunkLoad") void cmi$request(long pos);
    @Invoker("storeChunkSections") boolean cmi$store(long pos,Consumer<Entity> action);
}
