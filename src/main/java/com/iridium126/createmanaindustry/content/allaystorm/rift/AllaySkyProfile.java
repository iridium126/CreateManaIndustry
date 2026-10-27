package com.iridium126.createmanaindustry.content.allaystorm.rift;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Independent durable art seed. Never exposes the world-generation seed. */
public final class AllaySkyProfile extends SavedData {
    private final long seed;
    private AllaySkyProfile(){seed=net.minecraft.util.RandomSource.create().nextLong();setDirty();}
    private AllaySkyProfile(CompoundTag tag){seed=tag.getLong("VisualSeed");}
    public static AllaySkyContext context(ServerLevel level){
        var profile=level.getDataStorage().computeIfAbsent(new Factory<>(AllaySkyProfile::new,(tag,registries)->new AllaySkyProfile(tag)),"cmi_allay_sky");
        return new AllaySkyContext(profile.seed,level.getDayTime(),level.getGameTime(),level.getRainLevel(1),level.getThunderLevel(1),2,level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DAYLIGHT),level.getDayTimeFraction(),level.getDayTimePerTick());
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries){tag.putLong("VisualSeed",seed);return tag;}
}
