package com.iridium126.createmanaindustry.content.allaystorm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class StormRingGameTests {
    private static final Vec3 EAST = new Vec3(1, 0, 0);

    @GameTest(template="storm_combat_test", timeoutTicks=40)
    public static void beamPenetratesPlayersOnlyOnce(GameTestHelper helper) throws ReflectiveOperationException {
        var level = helper.getLevel();
        var startBlock=helper.absolutePos(new BlockPos(1,20,1)).atY(200);
        for(int i=0;i<=48;i++)level.setBlock(startBlock.east(i),Blocks.AIR.defaultBlockState(),2);
        Vec3 origin = Vec3.atCenterOf(startBlock);
        var first = player(level,"StormBeamFirst");
        var second = player(level,"StormBeamSecond");
        try {
            var protection = net.minecraft.server.level.ServerPlayer.class.getDeclaredField("spawnInvulnerableTime");
            protection.setAccessible(true);
            protection.setInt(first, 0);
            protection.setInt(second, 0);
            first.setPos(origin.add(2, -.5, 0));
            second.setPos(origin.add(6, -.5, 0));
            long start = level.getGameTime();
            var beam = new StormRingBeam(level, origin, EAST, start);
            helper.assertTrue(beam.shot.distance()==48, "Open beam lost its 48-block range");
            var removed = new ArrayList<BlockPos>();
            beam.advance(level, start+1, removed);
            helper.assertTrue(first.getHealth()<20 && second.getHealth()==20, "Head did not advance by three blocks");
            float remaining=first.getHealth();
            first.invulnerableTime=0;
            first.setPos(origin.add(4, -.5, 0));
            beam.advance(level, start+2, removed);
            helper.assertTrue(first.getHealth()==remaining && second.getHealth()<20, "Beam stopped at a player or hit a player twice");
        } finally {
            level.removePlayerImmediately(first, Entity.RemovalReason.DISCARDED);
            level.removePlayerImmediately(second, Entity.RemovalReason.DISCARDED);
        }
        helper.succeed();
    }

    private static FakePlayer player(ServerLevel level,String name) {
        var player=new FakePlayer(level,new GameProfile(UUID.randomUUID(),name)){
            @Override public boolean isInvulnerableTo(net.minecraft.world.damagesource.DamageSource source){return false;}
        };
        player.setGameMode(GameType.SURVIVAL);
        level.addNewPlayer(player);
        return player;
    }

    @GameTest(template="storm_combat_test", timeoutTicks=40)
    public static void beamStopsAtBlockAndExplodesOnce(GameTestHelper helper) {
        var level = helper.getLevel();
        var wall = helper.absolutePos(new BlockPos(6, 30, 4));
        level.setBlockAndUpdate(wall, Blocks.WHITE_WOOL.defaultBlockState());
        Vec3 origin = Vec3.atCenterOf(wall.west(5));
        long now = level.getGameTime();
        var beam = new StormRingBeam(level, origin, EAST, now);
        helper.assertTrue(beam.shot.face()==net.minecraft.core.Direction.WEST && Math.abs(beam.shot.distance()-4.5)<.01,
                "Beam did not clip to the first collision face");
        var blasts = new int[1];
        Consumer<ExplosionEvent.Detonate> listener = event -> {
            if (event.getLevel()==level) blasts[0]++;
        };
        var removed = new ArrayList<BlockPos>();
        NeoForge.EVENT_BUS.addListener(listener);
        try {
            beam.advance(level,now+1,removed);
            helper.assertTrue(blasts[0]==0, "Blast ran ahead of the beam");
            beam.advance(level,now+2,removed);
            beam.advance(level,now+3,removed);
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
        }
        helper.assertTrue(blasts[0]==1 && removed.contains(wall) && level.isEmptyBlock(wall),
                "Expected one vanilla blast and the actual destroyed-cell delta");
        helper.succeed();
    }

    @GameTest(template="storm_combat_test", timeoutTicks=40)
    public static void blastHonorsGriefingAndProtection(GameTestHelper helper) {
        var level = helper.getLevel();
        var block = helper.absolutePos(new BlockPos(3, 30, 4));
        level.setBlockAndUpdate(block, Blocks.WHITE_WOOL.defaultBlockState());
        Vec3 point=Vec3.atCenterOf(block).add(-.5,0,0);
        var griefing=level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING);
        boolean previous=griefing.get();
        griefing.set(false,level.getServer());
        try {
            helper.assertTrue(StormBlast.explode(level, point).isEmpty() && !level.isEmptyBlock(block), "mobGriefing=false damaged terrain");
        } finally {
            griefing.set(previous,level.getServer());
        }
        Consumer<ExplosionEvent.Detonate> protect = event -> {
            if(event.getLevel()==level) event.getAffectedBlocks().remove(block);
        };
        NeoForge.EVENT_BUS.addListener(protect);
        try {
            helper.assertTrue(StormBlast.explode(level, point).isEmpty() && !level.isEmptyBlock(block), "Filtered block appeared in the edit delta");
        } finally {
            NeoForge.EVENT_BUS.unregister(protect);
        }
        helper.succeed();
    }

    @GameTest(template="storm_combat_test", timeoutTicks=40)
    public static void blastCapturesDependentBlockRemoval(GameTestHelper helper) {
        var level = helper.getLevel();
        var lower = helper.absolutePos(new BlockPos(3, 30, 4)).atY(200);
        var upper = lower.above();
        var door = Blocks.OAK_DOOR.defaultBlockState();
        level.setBlock(lower.below(), Blocks.BEDROCK.defaultBlockState(), 2);
        level.setBlock(lower, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 2);
        level.setBlock(upper, door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 2);
        Consumer<ExplosionEvent.Detonate> directHitOnly = event -> {
            if (event.getLevel() == level) event.getAffectedBlocks().removeIf(pos -> !pos.equals(lower));
        };
        NeoForge.EVENT_BUS.addListener(directHitOnly);
        try {
            var removed = StormBlast.explode(level, Vec3.atCenterOf(lower));
            helper.assertTrue(level.isEmptyBlock(upper) && removed.contains(lower) && removed.contains(upper),
                    "Blast missed the collision loss from a neighbor update");
        } finally {
            NeoForge.EVENT_BUS.unregister(directHitOnly);
        }
        helper.succeed();
    }

    @GameTest(template="storm_combat_test", timeoutTicks=40)
    public static void optimizedBlastMatchesVanilla(GameTestHelper helper) {
        var level=helper.getLevel();
        var first=helper.absolutePos(new BlockPos(3,45,4));
        var second=first.east(24);
        var a=new ArrayList<BlockPos>();
        var b=new ArrayList<BlockPos>();
        for(int x=-4;x<=4;x++)for(int y=-2;y<=2;y++)for(int z=-4;z<=4;z++){
            if(x<0)continue;
            var state=(x+7*y+5*z)%5==0?Blocks.COBBLESTONE.defaultBlockState():Blocks.WHITE_WOOL.defaultBlockState();
            var left=first.offset(x,y,z);var right=second.offset(x,y,z);
            level.setBlock(left,state,2);level.setBlock(right,state,2);
            a.add(left);b.add(right);
        }
        var origin=Vec3.atCenterOf(first).add(-.5001,0,0);
        level.random.setSeed(6412);
        level.explode(null,origin.x,origin.y,origin.z,5,false,net.minecraft.world.level.Level.ExplosionInteraction.MOB);
        level.random.setSeed(6412);
        var removed=StormBlast.explode(level,origin.add(24,0,0));
        for(int i=0;i<a.size();i++){
            helper.assertTrue(level.isEmptyBlock(a.get(i))==level.isEmptyBlock(b.get(i)),"Power-five terrain footprint differs from vanilla");
            helper.assertTrue(removed.contains(b.get(i))==level.isEmptyBlock(b.get(i)),"Reported edit differs from completed explosion");
        }
        helper.succeed();
    }

    @GameTest(template="storm_combat_test", timeoutTicks=40)
    public static void simultaneousRingsReserveAndRetireIndependently(GameTestHelper helper) {
        var level=helper.getLevel();
        var origin=helper.absolutePos(new BlockPos(3,36,4));
        var first=player(level,"StormFirstRing");
        var second=player(level,"StormSecondRing");
        try {
            first.setPos(Vec3.atCenterOf(origin));second.setPos(Vec3.atCenterOf(origin.east()));
            var data=AllayStormData.create(origin,128,level.getGameTime(),17);
            var center=Vec3.atCenterOf(origin.above(30));
            var reserved=new BitSet();reserved.set(0,5);
            var attacks=new StormRings();
            var nextId=new java.util.concurrent.atomic.AtomicInteger();
            var players=List.<net.minecraft.server.level.ServerPlayer>of(first,second);
            var packets=new ArrayList<com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormRingPacket>();
            attacks.tick(level,data,center,players,players,reserved,(m,point,margin)->{},
                    StormNearbyRoster.lazy(data,center,level.getGameTime()+4,reserved),
                    nextId::incrementAndGet,packets::add,packet->{});
            helper.assertTrue(attacks.active()==2&&reserved.cardinality()==37&&packets.getFirst().frames().size()==2,
                    "Both independent rings should reserve disjoint 16-member groups");
            int member=packets.getFirst().frames().getFirst().roster()[0];
            attacks.dead(member,reserved);
            helper.assertTrue(reserved.cardinality()==36&&!reserved.get(member),"Dead ring member did not release");
            attacks.tick(level,data,center,players,List.of(),reserved,(m,point,margin)->{},
                    ()->{throw new AssertionError("No candidate search expected");},
                    nextId::incrementAndGet,packets::add,packet->{});
            helper.assertTrue(attacks.active()==2,"A single ring member death aborted the ring");
            attacks.tick(level,data,center,List.of(),List.of(),reserved,(m,point,margin)->{},
                    ()->{throw new AssertionError("No candidate search expected");},
                    nextId::incrementAndGet,packets::add,packet->{});
            helper.assertTrue(attacks.active()==0&&reserved.cardinality()==5&&packets.getLast().frames().size()==2,
                    "Target departure did not release only the two ring rosters");
        } finally {
            level.removePlayerImmediately(first,Entity.RemovalReason.DISCARDED);
            level.removePlayerImmediately(second,Entity.RemovalReason.DISCARDED);
        }
        helper.succeed();
    }

    @GameTest(template="storm_combat_test", timeoutTicks=120)
    public static void concurrentPowerFiveBlastProfile(GameTestHelper helper) {
        var level = helper.getLevel();
        var base = helper.absolutePos(new BlockPos(2, 150, 2));
        var centers = new BlockPos[64];
        for (int i=0;i<centers.length;i++) {
            centers[i]=base.offset((i&7)*10,0,(i>>>3)*10);
            var p=centers[i];
            level.getChunk((p.getX()-6)>>4,(p.getZ()-6)>>4);
            level.getChunk((p.getX()+6)>>4,(p.getZ()+6)>>4);
        }
        double[][] samples=new double[4][16];
        for (int batch=0;batch<80;batch++) {
            int iteration=batch,count=batch<40?16:64;
            boolean optimized=(batch&1)==1;
            helper.runAtTickTime(2+batch,()->{
                for(int i=0;i<count;i++) {
                    var center=centers[i];
                    for(int x=0;x<2;x++)for(int y=-1;y<=1;y++)for(int z=-1;z<=1;z++)
                        level.setBlock(center.offset(x,y,z),Blocks.WHITE_WOOL.defaultBlockState(),2);
                }
                long start=System.nanoTime();
                int removed=0;
                for(int i=0;i<count;i++){
                    var point=Vec3.atCenterOf(centers[i]).add(-.5,0,0);
                    if(optimized)removed+=StormBlast.explode(level,point).size();
                    else level.explode(null,point.x,point.y,point.z,5,false,net.minecraft.world.level.Level.ExplosionInteraction.MOB);
                }
                double millis=(System.nanoTime()-start)/1e6;
                helper.assertTrue(!optimized||removed>=count,"A concurrent blast failed to execute");
                int sample=(iteration%40)/2-4;
                if(sample>=0)samples[(count==16?0:2)+(optimized?1:0)][sample]=millis;
                if(iteration==79){
                    for(int row=0;row<samples.length;row++){
                        Arrays.sort(samples[row]);
                        System.out.printf(java.util.Locale.ROOT,"Storm blast %s (%d): p50 %.3f ms, p95 %.3f ms%n",
                                (row&1)==1?"optimized":"vanilla",row<2?16:64,samples[row][7],samples[row][15]);
                    }
                    helper.succeed();
                }
            });
        }
    }
}
