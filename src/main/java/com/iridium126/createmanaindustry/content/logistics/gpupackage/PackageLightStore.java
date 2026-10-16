package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import com.simibubi.create.content.logistics.box.PackageEntity;
import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;

/** Durable package backing records, outside entity ticking, collision and native tracking.
 * Position updates touch one spatial bucket. Only disabling server authority restores entities. */
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
        int witherTicks;
        net.minecraft.world.effect.MobEffectInstance witherEffect;
        BlockPos pendingPortal;
        int pendingPortalType,pendingPortalAge;
        boolean pendingPortalZ;
        long portalUpdatedTick,environmentStep;
        float health=5;
        PackageEnvironmentMotion environmentMotion=PackageEnvironmentMotion.EMPTY;
        public PackageEnvironmentMotion environmentMotion(){return new PackageEnvironmentMotion(environmentMotion.frozenTicks(),environmentMotion.fallDistance(),environmentMotion.stuckX(),environmentMotion.stuckY(),environmentMotion.stuckZ(),insertionDelay,environmentMotion.age());}
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
                entity.getBbWidth(),entity.getBbHeight(),entity.getId(),data,state);entry.insertionDelay=entity.insertionDelay;entry.health=entity.getHealth();entry.fireTicks=Math.clamp(entity.getRemainingFireTicks(),-20,72000);entry.portalCooldown=entity.getPortalCooldown();entry.portalUpdatedTick=entity.level().getGameTime();entry.tossedBy=((PackageInitialEntityAccess)entity).cmi$tossedBy();
        entry.witherEffect=PackageLightWither.copy(entity.getEffect(net.minecraft.world.effect.MobEffects.WITHER));entry.witherTicks=entry.witherEffect==null?0:entry.witherEffect.getDuration();
        var stuck=((PackageInitialEntityAccess)entity).cmi$stuckMultiplier();
        entry.environmentMotion=new PackageEnvironmentMotion(Math.clamp(entity.getTicksFrozen(),0,140),Math.max(0,entity.fallDistance),(float)stuck.x,(float)stuck.y,(float)stuck.z,entry.insertionDelay,Math.max(0,entity.tickCount));
        if(entity.portalProcess!=null&&entity.portalProcess.isInsidePortalThisTick()){
            var process=entity.portalProcess;var blocks=new net.minecraft.world.level.block.Block[]{net.minecraft.world.level.block.Blocks.NETHER_PORTAL,net.minecraft.world.level.block.Blocks.END_PORTAL,net.minecraft.world.level.block.Blocks.END_GATEWAY};
            for(int i=0;i<blocks.length;i++)if(process.isSamePortal((net.minecraft.world.level.block.Portal)blocks[i])){
                entry.pendingPortal=process.getEntryPosition();entry.pendingPortalType=i+1;entry.pendingPortalAge=entry.environmentMotion.age();
                var block=entity.level().getBlockState(entry.pendingPortal);entry.pendingPortalZ=block.hasProperty(net.minecraft.world.level.block.NetherPortalBlock.AXIS)&&block.getValue(net.minecraft.world.level.block.NetherPortalBlock.AXIS)==net.minecraft.core.Direction.Axis.Z;
            }
        }
        put(entry);return entry;
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
            entry.insertionDelay=row.contains("InsertionDelay")?Math.clamp(row.getInt("InsertionDelay"),0,30):30;entry.health=row.contains("Health")?row.getFloat("Health"):5;entry.fireTicks=Math.clamp(row.getInt("FireTicks"),-20,72000);entry.environmentStep=row.getLong("EnvironmentStep");entry.portalCooldown=Math.max(0,row.getInt("PortalCooldown"));if(row.hasUUID("TossedBy"))entry.tossedBy=row.getUUID("TossedBy");store.put(entry);
            if(row.contains("EnvironmentMotion")){var motion=row.getCompound("EnvironmentMotion");entry.environmentMotion=new PackageEnvironmentMotion(Math.clamp(motion.getInt("Frozen"),0,140),Math.max(0,motion.getFloat("Fall")),motion.getFloat("StuckX"),motion.getFloat("StuckY"),motion.getFloat("StuckZ"),entry.insertionDelay,Math.max(0,motion.getInt("Age")));}
            else {
                // Older records already retained vanilla entity NBT. Use its known
                // frozen/fall/fire state before falling back to zero/default values.
                entry.environmentMotion=new PackageEnvironmentMotion(Math.clamp(entry.data.getInt("TicksFrozen"),0,140),Math.max(0,entry.data.getFloat("FallDistance")),0,0,0,entry.insertionDelay);
                if(entry.fireTicks==0&&entry.data.contains("Fire"))entry.fireTicks=Math.clamp(entry.data.getShort("Fire"),-20,0);
            }
            entry.witherTicks=Math.clamp(row.getInt("WitherTicks"),-1,72000);
            if(row.contains("WitherEffect")){
                var effect=net.minecraft.world.effect.MobEffectInstance.load(row.getCompound("WitherEffect"));
                if(effect!=null&&effect.is(net.minecraft.world.effect.MobEffects.WITHER)){entry.witherEffect=effect;entry.witherTicks=effect.getDuration();}
            }else if(!row.contains("WitherTicks")){
                var effects=entry.data.getList("active_effects",Tag.TAG_COMPOUND);
                for(int n=0;n<effects.size();n++){
                    var effect=net.minecraft.world.effect.MobEffectInstance.load(effects.getCompound(n));
                    if(effect!=null&&effect.is(net.minecraft.world.effect.MobEffects.WITHER)){entry.witherEffect=effect;entry.witherTicks=effect.getDuration();break;}
                }
            }
            if(row.contains("PendingPortal")){var portal=row.getCompound("PendingPortal");int type=portal.getInt("Type");if(type>=1&&type<=3){entry.pendingPortal=new net.minecraft.core.BlockPos(portal.getInt("X"),portal.getInt("Y"),portal.getInt("Z"));entry.pendingPortalType=type;entry.pendingPortalAge=Math.max(0,portal.getInt("Age"));entry.pendingPortalZ=portal.getBoolean("AxisZ");}}
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
            var motion=new CompoundTag();motion.putInt("Frozen",e.environmentMotion.frozenTicks());motion.putFloat("Fall",e.environmentMotion.fallDistance());motion.putFloat("StuckX",e.environmentMotion.stuckX());motion.putFloat("StuckY",e.environmentMotion.stuckY());motion.putFloat("StuckZ",e.environmentMotion.stuckZ());motion.putInt("Age",e.environmentMotion.age());row.put("EnvironmentMotion",motion);
            row.putInt("WitherTicks",e.witherTicks);
            if(e.witherEffect!=null)row.put("WitherEffect",e.witherEffect.save());
            if(e.pendingPortal!=null){var portal=new CompoundTag();portal.putInt("Type",e.pendingPortalType);portal.putInt("X",e.pendingPortal.getX());portal.putInt("Y",e.pendingPortal.getY());portal.putInt("Z",e.pendingPortal.getZ());portal.putInt("Age",e.pendingPortalAge);portal.putBoolean("AxisZ",e.pendingPortalZ);row.put("PendingPortal",portal);}
        }
        tag.put("Packages",rows);return tag;
    }
}
