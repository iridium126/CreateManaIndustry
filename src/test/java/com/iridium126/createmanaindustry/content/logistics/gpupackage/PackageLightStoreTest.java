package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

class PackageLightStoreTest {
    private static PackageAuthorityRegion.Snapshot pose(double x,double y,double z){return new PackageAuthorityRegion.Snapshot(new PackageLease.Pose(x,y,z,1,2,3,90),PackageAuthorityRegion.GROUNDED);}
    private static PackageLightStore.Entry entry(int id,double x,double y,double z){var data=new CompoundTag();var box=new CompoundTag();box.putString("id","create:cardboard_package_12x12");box.putInt("count",1);data.put("Box",box);data.putString("Custom","preserved");return new PackageLightStore.Entry(new PackageLease.Identity(id,1),UUID.randomUUID(),ResourceLocation.parse("create:cardboard_package_12x12"),.75f,.75f,id,data,pose(x,y,z));}
    @Test void persistentPoseAndContentsSurviveWithoutAnEntity(){var store=new PackageLightStore();var e=entry(1,-64.1,70,64.1);store.put(e);store.update(e,pose(65,72,-65));var saved=store.save(new CompoundTag(),null);var restored=PackageLightStore.load(saved,null);var loaded=restored.entries().iterator().next();assertEquals(e.identity,loaded.identity);assertEquals(e.uuid,loaded.uuid);assertEquals(e.state(),loaded.state());assertEquals(e.data,loaded.data);assertEquals(-1,loaded.entityId);assertEquals(1,restored.query(new AABB(64,71,-66,66,74,-64)).size());}
    @Test void spatialIndexTracksRegionCrossingAndRemoval(){var store=new PackageLightStore();var e=entry(1,-.1,64,-.1);store.put(e);assertEquals(1,store.query(new AABB(-1,63,-1,1,66,1)).size());store.update(e,pose(128,128,128));assertTrue(store.query(new AABB(-1,63,-1,1,66,1)).isEmpty());assertEquals(1,store.query(new AABB(127,127,127,129,130,129)).size());store.remove(e);assertTrue(store.entries().isEmpty());assertNull(store.byId(1));assertNull(store.byUuid(e.uuid));}
    @Test void enormousQueriesCannotOverflowTheBucketLoop(){var store=new PackageLightStore();store.put(entry(1,0,0,0));assertEquals(1,store.query(new AABB(-Double.MAX_VALUE,-Double.MAX_VALUE,-Double.MAX_VALUE,Double.MAX_VALUE,Double.MAX_VALUE,Double.MAX_VALUE)).size());}
    @Test void saturatedSingleAxisDoesNotWrapTheLoopCounter(){var store=new PackageLightStore();store.put(entry(1,0,0,0));assertTrue(store.query(new AABB(1e20,-1,-1,1e21,1,1)).isEmpty());}
    @Test void duplicateBackingDataCannotAliasAnotherPackage(){var store=new PackageLightStore();var e=entry(1,0,0,0);store.put(e);assertThrows(IllegalArgumentException.class,()->store.put(entry(1,10,0,0)));assertEquals(1,store.entries().size());}
    @Test void chunkIndexMovesEvenWithinTheSamePhysicsRegion(){var store=new PackageLightStore();var e=entry(1,15,70,15);store.put(e);assertEquals(1,store.inChunk(new net.minecraft.world.level.ChunkPos(0,0)).size());store.update(e,pose(16,70,16));assertTrue(store.inChunk(new net.minecraft.world.level.ChunkPos(0,0)).isEmpty());assertEquals(1,store.inChunk(new net.minecraft.world.level.ChunkPos(1,1)).size());assertSame(e,store.byIdentity(e.identity));store.remove(e);assertTrue(store.inChunk(new net.minecraft.world.level.ChunkPos(1,1)).isEmpty());}
}
