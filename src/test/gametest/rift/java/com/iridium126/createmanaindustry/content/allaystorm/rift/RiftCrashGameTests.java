package com.iridium126.createmanaindustry.content.allaystorm.rift;

import com.iridium126.createmanaindustry.content.allaystorm.AllayStormData;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.vehicle.ChestBoat;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;
import java.nio.file.*;
import java.util.*;

/** Opt-in process-death tests, compiled only into the isolated GameTest launch. Never in the mod jar. */
@GameTestHolder("createmanaindustry") @PrefixGameTestTemplate(false)
public final class RiftCrashGameTests {
    @GameTest(template="rift_transport_test",timeoutTicks=3000)
    public static void durableGroupSurvivesProcessDeath(GameTestHelper helper)throws Exception {
        String phase=System.getProperty("cmi.test.riftFault");if(phase==null){helper.succeed();return;}
        var source=helper.getLevel();var server=source.getServer();Path root=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        if(!root.startsWith(Path.of(".").toAbsolutePath().normalize())||!root.toString().replace('\\','/').contains("/build/gametest/"))throw new IllegalStateException("Fault injection requires an isolated build/gametest world");
        Path manifest=root.resolve("rift-fault-fixture.dat");
        if("sable".equals(System.getProperty("cmi.test.riftFaultVehicle"))){RiftSableCrashFixture.run(helper,manifest,phase);return;}
        if("recover".equals(System.getProperty("cmi.test.riftFaultMode"))){recover(helper,NbtIo.readCompressed(manifest,NbtAccounter.unlimitedHeap()));return;}
        if(Files.exists(manifest))throw new IllegalStateException("Crash fixture already exists; choose a fresh isolated run directory");
        var target=server.getLevel(AllvrDimensions.ALLAY_LEVEL);var first=RiftTransportGameTests.player(source);var second=RiftTransportGameTests.player(source);
        Vec3 p=Vec3.atCenterOf(helper.absolutePos(new BlockPos(3,3,3)));double y=source.getMaxBuildHeight()+110;
        var boat=EntityType.CHEST_BOAT.create(source);boat.setNoGravity(true);boat.moveTo(p.x,y,p.z,37,0);boat.setItem(0,new ItemStack(Items.DIAMOND,17));source.addFreshEntity(boat);
        var pig=EntityType.PIG.create(source);pig.setNoAi(true);pig.setNoGravity(true);pig.moveTo(p.x,y+1,p.z,55,0);source.addFreshEntity(pig);pig.startRiding(boat,true);first.startRiding(boat,true);second.startRiding(pig,true);first.setPos(p.x,y+2,p.z);second.setPos(p.x,y+3,p.z);
        for(Entity e:List.of(boat,pig,first,second))RiftMountPersistence.mark(e);
        var baseline=new RiftNativeCheckpoint(RiftTransferCoordinator.current().journal,Set.of(boat.getUUID(),pig.getUUID()));baseline.include(source,List.of(boat.chunkPosition(),pig.chunkPosition()));baseline.player(first);baseline.player(second);
        var saved=new CompoundTag();saved.putUUID("Boat",boat.getUUID());saved.putUUID("Pig",pig.getUUID());saved.putUUID("First",first.getUUID());saved.putUUID("Second",second.getUUID());saved.putInt("SourceX",boat.chunkPosition().x);saved.putInt("SourceZ",boat.chunkPosition().z);saved.putString("Phase",phase);
        if(phase.equals("PARTIAL")){java.util.function.Consumer<net.neoforged.neoforge.event.entity.EntityJoinLevelEvent> delay=event->{if(event.getLevel()==target&&(event.getEntity().getUUID().equals(boat.getUUID())||event.getEntity().getUUID().equals(pig.getUUID()))){long end=System.nanoTime()+1_200_000;while(System.nanoTime()<end)Thread.onSpinWait();}};net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(delay);}
        var storm=new AllayStormData();storm.active=true;storm.count=16384;storm.stormSeed=738;boolean[] initialized={false};
        helper.onEachTick(()->{
            if(!initialized[0]){if(!baseline.step(new RiftWorkBudget(System.nanoTime()+1_000_000,2)))return;try{NbtIo.writeCompressed(saved,manifest);}catch(Exception e){throw new AssertionError(e);}initialized[0]=true;}
            if(!StormRiftTransfers.retained(first.getUUID())&&first.level()==source)StormRiftTransfers.consider(source,storm,p.x,p.z,target);
            for(var op:operations()) {
                if(!op.players.contains(first.getUUID()))continue;boolean stop=switch(phase){
                    case "PREPARING"->op.machine.stage()==RiftTransaction.Stage.PREPARING;
                    case "SNAPSHOTTING"->op.machine.stage()==RiftTransaction.Stage.SNAPSHOTTING;
                    case "PREPARED"->op.machine.stage()==RiftTransaction.Stage.COMMITTING&&op.commitCursor==0;
                    case "PARTIAL"->op.machine.stage()==RiftTransaction.Stage.COMMITTING&&op.commitCursor>0&&op.commitCursor<op.group.members().size();
                    case "COMMITTED"->op.machine.stage()==RiftTransaction.Stage.PUBLISHING;
                    case "CHECKPOINTED"->op.published;
                    default->throw new IllegalArgumentException("Unknown fault phase "+phase);
                };
                if(stop){System.out.println("CMI_RIFT_EXPECTED_PROCESS_DEATH "+phase);System.out.flush();Runtime.getRuntime().halt(74);}
            }
        });
    }
    @SuppressWarnings("unchecked") static Collection<RiftTransferCoordinator.Operation> operations(){try{var f=RiftTransferCoordinator.class.getDeclaredField("active");f.setAccessible(true);return List.copyOf(((Map<UUID,RiftTransferCoordinator.Operation>)f.get(RiftTransferCoordinator.current())).values());}catch(Exception e){throw new AssertionError(e);}}
    static void recover(GameTestHelper helper,CompoundTag saved) {
        var source=helper.getLevel();var server=source.getServer();var target=server.getLevel(AllvrDimensions.ALLAY_LEVEL);boolean committed=Set.of("COMMITTED","CHECKPOINTED").contains(saved.getString("Phase"));var canonical=committed?target:source;
        var tickets=new RiftChunkTickets(source,UUID.randomUUID());tickets.include(new ChunkPos(saved.getInt("SourceX"),saved.getInt("SourceZ")));ServerPlayer[] players=new ServerPlayer[2];
        helper.onEachTick(()->{if(!tickets.step(new RiftWorkBudget(System.nanoTime()+1_000_000,2)))return;if(!StormRiftTransfers.report().contains("rift-transfer=active"))return;if(players[0]==null){players[0]=RiftTransportGameTests.player(source,saved.getUUID("First"));players[1]=RiftTransportGameTests.player(source,saved.getUUID("Second"));}});
        helper.succeedWhen(()->{
            helper.assertTrue(players[0]!=null&&players[1]!=null,"Recovery/player file readiness pending: "+StormRiftTransfers.report());
            helper.assertTrue(players[0].level()==canonical&&players[1].level()==canonical,"Offline companions recovered to different/wrong worlds");
            Entity boat=canonical.getEntity(saved.getUUID("Boat")),pig=canonical.getEntity(saved.getUUID("Pig"));helper.assertTrue(boat instanceof ChestBoat&&pig!=null,"Canonical inventory vehicle or nested member missing");
            helper.assertTrue(players[0].getVehicle()==boat&&players[1].getVehicle()==pig&&pig.getVehicle()==boat,"Recovered mounting tree missing");helper.assertTrue(((ChestBoat)boat).getItem(0).is(Items.DIAMOND)&&((ChestBoat)boat).getItem(0).getCount()==17,"Recovered inventory changed");
            for(ServerLevel world:server.getAllLevels())if(world!=canonical)helper.assertTrue(world.getEntity(saved.getUUID("Boat"))==null&&world.getEntity(saved.getUUID("Pig"))==null,"Noncanonical entity copy remains");
            helper.assertTrue(!StormRiftTransfers.frozen(boat.getUUID())&&!StormRiftTransfers.frozen(players[0].getUUID()),"Recovery locks leaked");tickets.close();
        });
    }
}
