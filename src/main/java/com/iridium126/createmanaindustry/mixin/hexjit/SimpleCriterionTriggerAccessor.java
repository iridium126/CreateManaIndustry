package com.iridium126.createmanaindustry.mixin.hexjit;

import java.util.Map;
import java.util.Set;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.PlayerAdvancements;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to the listener index for the specialized Hexcasting media trigger. */
@Mixin(SimpleCriterionTrigger.class)
public interface SimpleCriterionTriggerAccessor {
    @Accessor("players")
    Map<PlayerAdvancements, Set<CriterionTrigger.Listener<?>>> cmi$getPlayers();
}
