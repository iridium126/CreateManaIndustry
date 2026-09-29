package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Server-wide durable, never-reused IDs and session epochs. No item data is stored here. */
public final class PackageIdentityData extends SavedData {
    private static final Factory<PackageIdentityData> FACTORY=new Factory<>(PackageIdentityData::new,PackageIdentityData::load,null);
    private long nextIdentity=1,nextEpoch=1;
    public static PackageIdentityData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY,"cmi_gpu_packages");
    }
    static PackageIdentityData load(CompoundTag tag,HolderLookup.Provider registries) {
        PackageIdentityData data=new PackageIdentityData();
        data.nextIdentity=Math.max(1,tag.getLong("NextIdentity"));data.nextEpoch=Math.max(1,tag.getLong("NextEpoch"));return data;
    }
    public long identity(){long id=nextIdentity;nextIdentity=Math.incrementExact(nextIdentity);setDirty();return id;}
    public long epoch(){long id=nextEpoch;nextEpoch=Math.incrementExact(nextEpoch);setDirty();return id;}
    /** A restored object may predate this SavedData file; its ID must still never be reallocated. */
    public void observe(long identity) {
        if(identity>=nextIdentity){nextIdentity=Math.incrementExact(identity);setDirty();}
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries) {
        tag.putLong("NextIdentity",nextIdentity);tag.putLong("NextEpoch",nextEpoch);return tag;
    }
}
