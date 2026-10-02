package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import com.simibubi.create.content.logistics.box.PackageEntity;
import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;

/** Durable package backing records, outside entity ticking, collision and native tracking.
 * Position updates touch one spatial bucket. Gameplay never constructs a replacement entity. */
public final class PackageLightStore extends SavedData {
    private static final Factory<PackageLightStore> FACTORY=new Factory<>(PackageLightStore::new,PackageLightStore::load,null);
    public static final class Entry {
        public final PackageLease.Identity identity;
        public final UUID uuid;
        public final ResourceLocation model;
        public final float width,height;
        public final int entityId;
        final CompoundTag data;
        PackageAuthorityRegion.Snapshot state;
        private net.minecraft.world.item.ItemStack box;
        int insertionDelay=30;
        int fireTicks,portalCooldown;
        long portalUpdatedTick,environmentStep;
        float health=5;
        UUID tossedBy;
        Entry(PackageLease.Identity identity,UUID uuid,ResourceLocation model,float width,float height,int entityId,
                CompoundTag data,PackageAuthorityRegion.Snapshot state) {
            this.identity=Objects.requireNonNull(identity);this.uuid=Objects.requireNonNull(uuid);this.model=Objects.requireNonNull(model);
            if(!Float.isFinite(width)||!Float.isFinite(height)||width<=0||height<=0||width>16||height>16)throw new IllegalArgumentException("Light package dimensions");
            this.width=width;this.height=height;this.entityId=entityId;this.data=data.copy();this.state=Objects.requireNonNull(state);
        }
        public int fireTicks(){return fireTicks;}
        public float health(){return health;}
        public boolean portalReady(long tick){if(portalCooldown>0){portalCooldown=(int)Math.max(0,portalCooldown-Math.max(0,tick-portalUpdatedTick));portalUpdatedTick=tick;}return portalCooldown==0;}
        public PackageAuthorityRegion.Snapshot state(){return state;}
        public AABB bounds(){var p=state.pose();double r=width*.5;return new AABB(p.x()-r,p.y(),p.z()-r,p.x()+r,p.y()+height,p.z()+r);}
        public net.minecraft.world.item.ItemStack box(ServerLevel level){if(box==null)box=net.minecraft.world.item.ItemStack.parseOptional(level.registryAccess(),data.getCompound("Box"));return box;}
        public void replaceBox(ServerLevel level,net.minecraft.world.item.ItemStack remainder){
            box=remainder.copy();data.put("Box",box.save(level.registryAccess()));
        }
        public net.minecraft.world.phys.Vec3 position(){var p=state.pose();return new net.minecraft.world.phys.Vec3(p.x(),p.y(),p.z());}
    }
    private final Map<PackageLease.Identity,Entry> entries=new LinkedHashMap<>();
    private final Map<UUID,Entry> uuids=new HashMap<>();
    private final Map<Integer,Entry> ids=new HashMap<>();
    private final Map<PackageRegion,Set<Entry>> spatial=new HashMap<>();
    private final Map<net.minecraft.world.level.ChunkPos,Set<Entry>> chunks=new HashMap<>();
    public static PackageLightStore get(ServerLevel level){return level.getDataStorage().computeIfAbsent(FACTORY,"cmi_light_packages");}
    public Entry capture(PackageEntity entity,PackageLease.Identity identity,PackageAuthorityRegion.Snapshot state) {
        var data=new CompoundTag();entity.saveWithoutId(data);
        var entry=new Entry(identity,entity.getUUID(),BuiltInRegistries.ITEM.getKey(entity.box.getItem()),
                entity.getBbWidth(),entity.getBbHeight(),entity.getId(),data,state);entry.insertionDelay=entity.insertionDelay;entry.health=entity.getHealth();entry.fireTicks=Math.max(0,entity.getRemainingFireTicks());entry.portalCooldown=entity.getPortalCooldown();entry.portalUpdatedTick=entity.level().getGameTime();var thrower=entity.tossedBy.get();if(thrower!=null)entry.tossedBy=thrower.getUUID();put(entry);return entry;
    }
    void put(Entry entry) {
        if(entries.containsKey(entry.identity)||uuids.containsKey(entry.uuid)||entry.entityId>=0&&ids.containsKey(entry.entityId))
            throw new IllegalArgumentException("Duplicate lightweight package");
        entries.put(entry.identity,entry);uuids.put(entry.uuid,entry);if(entry.entityId>=0)ids.put(entry.entityId,entry);
        bucket(entry.state.pose()).add(entry);chunks.computeIfAbsent(chunk(entry.state.pose()),k->new LinkedHashSet<>()).add(entry);setDirty();
    }
    private Set<Entry> bucket(PackageLease.Pose pose){return spatial.computeIfAbsent(PackageRegion.at(pose),k->new LinkedHashSet<>());}
    private static net.minecraft.world.level.ChunkPos chunk(PackageLease.Pose pose){return new net.minecraft.world.level.ChunkPos(net.minecraft.core.BlockPos.containing(pose.x(),pose.y(),pose.z()));}
    private void unchunk(Entry entry){var key=chunk(entry.state.pose());var rows=chunks.get(key);if(rows!=null){rows.remove(entry);if(rows.isEmpty())chunks.remove(key);}}
    private void unindex(Entry entry){var key=PackageRegion.at(entry.state.pose());var rows=spatial.get(key);if(rows!=null){rows.remove(entry);if(rows.isEmpty())spatial.remove(key);}}
    public void update(Entry entry,PackageAuthorityRegion.Snapshot state) {
        if(entries.get(entry.identity)!=entry)throw new IllegalArgumentException("Unknown lightweight package");
        if(entry.state.equals(state))return;
        var before=PackageRegion.at(entry.state.pose());var after=PackageRegion.at(state.pose());
        var oldChunk=chunk(entry.state.pose());var newChunk=chunk(state.pose());if(!oldChunk.equals(newChunk)){unchunk(entry);chunks.computeIfAbsent(newChunk,k->new LinkedHashSet<>()).add(entry);}
        if(!before.equals(after)){unindex(entry);entry.state=state;bucket(state.pose()).add(entry);}else entry.state=state;
        setDirty();
    }
    public void remove(Entry entry){if(entries.remove(entry.identity,entry)){unindex(entry);unchunk(entry);uuids.remove(entry.uuid,entry);if(entry.entityId>=0)ids.remove(entry.entityId,entry);setDirty();}}
    public Entry byIdentity(PackageLease.Identity identity){return entries.get(identity);}
    public Collection<Entry> inChunk(net.minecraft.world.level.ChunkPos chunk){var rows=chunks.get(chunk);return rows==null?List.of():List.copyOf(rows);}
    public Entry byId(int id){return ids.get(id);}
    public Entry byUuid(UUID uuid){return uuids.get(uuid);}
    public Collection<Entry> entries(){return List.copyOf(entries.values());}
    public List<Entry> query(AABB bounds) {
        var result=new ArrayList<Entry>();
        int x0=(int)Math.floor((bounds.minX-16)/64),x1=(int)Math.floor((bounds.maxX+16)/64);
        int y0=(int)Math.floor((bounds.minY-16)/64),y1=(int)Math.floor((bounds.maxY+16)/64);
        int z0=(int)Math.floor((bounds.minZ-16)/64),z1=(int)Math.floor((bounds.maxZ+16)/64);
        long nx=(long)x1-x0+1,ny=(long)y1-y0+1,nz=(long)z1-z0+1;
        if(x0==Integer.MIN_VALUE||x1==Integer.MAX_VALUE||y0==Integer.MIN_VALUE||y1==Integer.MAX_VALUE||z0==Integer.MIN_VALUE||z1==Integer.MAX_VALUE||nx<=0||ny<=0||nz<=0||nx>4096||ny>4096||nz>4096||nx*ny*nz>4096){for(var e:entries.values())if(e.bounds().intersects(bounds))result.add(e);return result;}
        for(int x=x0;x<=x1;x++)for(int y=y0;y<=y1;y++)for(int z=z0;z<=z1;z++) {
            var rows=spatial.get(new PackageRegion(x,y,z));if(rows!=null)for(var e:rows)if(e.bounds().intersects(bounds))result.add(e);
        }
        return result;
    }
    static PackageLightStore load(CompoundTag tag,HolderLookup.Provider registries) {
        var store=new PackageLightStore();var rows=tag.getList("Packages",Tag.TAG_COMPOUND);
        for(int i=0;i<rows.size();i++) {
            var row=rows.getCompound(i);var identity=new PackageLease.Identity(row.getLong("Id"),row.getLong("Generation"));
            var p=new PackageLease.Pose(row.getDouble("X"),row.getDouble("Y"),row.getDouble("Z"),row.getFloat("Vx"),row.getFloat("Vy"),row.getFloat("Vz"),row.getFloat("Yaw"));
            var entry=new Entry(identity,row.getUUID("UUID"),ResourceLocation.parse(row.getString("Model")),row.getFloat("Width"),row.getFloat("Height"),-1,
                    row.getCompound("Data"),new PackageAuthorityRegion.Snapshot(p,row.getInt("Flags")));
            entry.insertionDelay=row.contains("InsertionDelay")?Math.clamp(row.getInt("InsertionDelay"),0,30):30;entry.health=row.contains("Health")?row.getFloat("Health"):5;entry.fireTicks=Math.max(0,row.getInt("FireTicks"));entry.environmentStep=row.getLong("EnvironmentStep");entry.portalCooldown=Math.max(0,row.getInt("PortalCooldown"));if(row.hasUUID("TossedBy"))entry.tossedBy=row.getUUID("TossedBy");store.put(entry);
        }
        return store;
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries) {
        var rows=new ListTag();
        for(var e:entries.values()) {
            var row=new CompoundTag();var p=e.state.pose();row.putLong("Id",e.identity.id());row.putLong("Generation",e.identity.generation());
            row.putUUID("UUID",e.uuid);row.putString("Model",e.model.toString());row.putFloat("Width",e.width);row.putFloat("Height",e.height);
            row.put("Data",e.data.copy());row.putDouble("X",p.x());row.putDouble("Y",p.y());row.putDouble("Z",p.z());
            row.putLong("EnvironmentStep",e.environmentStep);row.putInt("InsertionDelay",e.insertionDelay);row.putFloat("Health",e.health);row.putInt("FireTicks",e.fireTicks);row.putInt("PortalCooldown",e.portalCooldown);if(e.tossedBy!=null)row.putUUID("TossedBy",e.tossedBy);
            row.putFloat("Vx",p.vx());row.putFloat("Vy",p.vy());row.putFloat("Vz",p.vz());row.putFloat("Yaw",p.yaw());row.putInt("Flags",e.state.flags());rows.add(row);
        }
        tag.put("Packages",rows);return tag;
    }
}
