package com.iridium126.createmanaindustry.compat.trickster;

import com.iridium126.createmanaindustry.CMIComponents;
import com.iridium126.createmanaindustry.compat.trickster.anima.BatteryAnima;
import com.iridium126.createmanaindustry.compat.trickster.casting.*;
import com.iridium126.createmanaindustry.compat.trickster.partition.*;
import com.iridium126.createmanaindustry.compat.trickster.greattrick.*;
import at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv;
import at.petrak.hexcasting.common.lib.HexDataComponents;
import com.mojang.authlib.GameProfile;
import dev.enjarai.trickster.spell.*;
import dev.enjarai.trickster.spell.execution.*;
import dev.enjarai.trickster.spell.execution.source.PlayerSpellSource;
import dev.enjarai.trickster.spell.fragment.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.*;
import org.joml.Vector3d;
import java.util.*;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class PartitionGameTests {
    private static ServerPlayer player(GameTestHelper h){var sink=FakePlayerFactory.get(h.getLevel(),new GameProfile(UUID.randomUUID(),"PartitionTest"));var p=new ServerPlayer(h.getLevel().getServer(),h.getLevel(),sink.getGameProfile(),ClientInformation.createDefault());p.connection=sink.connection;p.setPos(h.absolutePos(BlockPos.ZERO).getCenter());p.getInventory().setItem(0,battery(1000000));return p;}
    private static ItemStack battery(long balance){var b=new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("hexcasting:battery")));b.set(HexDataComponents.MEDIA_MAX,640000000L);b.set(CMIComponents.ANIMA.get(),balance);return b;}
    private static PartitionBounds box(ServerPlayer p,int low,int high){var b=p.blockPosition();return new PartitionBounds(b.getX()+low,b.getY()+low,b.getZ()+low,b.getX()+high,b.getY()+high,b.getZ()+high);}
    private static SpellContext ctx(ServerPlayer p){return new SpellContext(new ExecutionState(List.of()),new PlayerSpellSource(p),new TickData());}
    private static VectorFragment vector(Vec3 p){return new VectorFragment(new Vector3d(p.x,p.y,p.z));}
    @GameTest(template="trickster_stock_test")
    public static void volumePaymentReplacementAndFailureAreAtomic(GameTestHelper h){var p=player(h);Object owner=new Object();var c=CastChains.attach(new CastChain(),p,owner);try(var scope=CastChains.enter(c)) {
        var b=box(p,0,1);long before=BatteryAnima.available(p);
        h.assertTrue(PartitionManager.create(p,c,b),"Create failed");h.assertTrue(BatteryAnima.available(p)==before-8000,"Wrong volume cost");var old=c.partition();
        p.getInventory().setItem(0,battery(0));h.assertTrue(!PartitionManager.create(p,c,box(p,0,2))&&old.equals(c.partition()),"Insufficient balance replaced old domain");
        p.getInventory().setItem(0,battery(30000));h.assertTrue(PartitionManager.create(p,c,box(p,0,2)),"Same-chain intersecting replacement failed");h.assertTrue(BatteryAnima.available(p)==3000,"Replacement did not charge full new volume");
        h.assertTrue(!PartitionManager.create(p,c,box(p,50,51)),"Out of range creation succeeded");h.succeed();
    }finally{CastChains.release(c,owner);}}
    @GameTest(template="trickster_stock_test")
    public static void multiBatteryPaymentAndBooleanIdentity(GameTestHelper h){var p=player(h);p.getInventory().setItem(0,battery(400));p.getInventory().setItem(1,battery(600));Object owner=new Object();var c=CastChains.attach(new CastChain(),p,owner);try(var scope=CastChains.enter(c)) {
        var args=List.<Fragment>of(vector(p.position()),vector(p.position()));
        h.assertTrue(GreatTricks.PARTITION.activate(ctx(p),args)==BooleanFragment.TRUE,"Success did not return true");h.assertTrue(BatteryAnima.available(p)==0,"Multi-battery debit failed");
        h.assertTrue(GreatTricks.PARTITION.activate(ctx(p),args)==BooleanFragment.FALSE,"Insufficient balance did not return false");
        h.assertTrue(GreatTricks.PARTITION.activate(ctx(p),List.of(new NumberFragment(0)))==BooleanFragment.FALSE,"Invalid arguments did not return false");h.succeed();
    }finally{CastChains.release(c,owner);}}
    @GameTest(template="trickster_stock_test")
    public static void globalRangeInversionAndSameOutsideMembership(GameTestHelper h){var p=player(h);Object owner=new Object();var c=CastChains.attach(new CastChain(),p,owner);try {
        h.assertTrue(PartitionManager.create(p,c,box(p,0,2)),"Create failed");var env=new StaffCastEnv(p,InteractionHand.MAIN_HAND);Vec3 outside=p.position().add(4,0,0);
        h.assertTrue(!env.isVecInRange(outside),"Independent casting crossed domain");
        try(var scope=CastChains.enter(c)){c.toggle();h.assertTrue(env.isVecInRange(outside),"Inversion did not complement infinite reach");c.toggle();}
        var from=p.position().add(-2,0,0);h.assertTrue(!PartitionManager.crosses(p.level(),from,outside),"Outside-to-outside counted as crossing");
        CastChains.release(c,owner);h.assertTrue(env.isVecInRange(outside),"Chain end retained domain");h.succeed();
    }finally{CastChains.release(c,owner);}}
    @GameTest(template="trickster_stock_test")
    public static void nestedDomainsAndContactConflict(GameTestHelper h){var p=player(h);Object a=new Object(),b=new Object();var c=CastChains.attach(new CastChain(),p,a);var d=CastChains.attach(new CastChain(),p,b);try {
        h.assertTrue(PartitionManager.create(p,c,box(p,-3,3)),"Outer failed");h.assertTrue(PartitionManager.create(p,d,box(p,-1,1)),"Strict nesting failed");
        var saved=d.partition();long balance=BatteryAnima.available(p);h.assertTrue(!PartitionManager.create(p,d,box(p,-3,0)),"Touching boundaries accepted");
        h.assertTrue(d.partition().equals(saved)&&BatteryAnima.available(p)==balance,"Failed conflict mutated state");h.succeed();
    }finally{CastChains.release(c,a);CastChains.release(d,b);}}
    @GameTest(template="trickster_stock_test")
    public static void sweptMovementAndStandardTeleportsCannotCross(GameTestHelper h){var p=player(h);Object owner=new Object();var c=CastChains.attach(new CastChain(),p,owner);try {
        var b=box(p,0,2);h.assertTrue(PartitionManager.create(p,c,b),"Create failed");var cow=h.spawn(EntityType.COW,new BlockPos(1,1,1));
        cow.setPos(b.minX()+1.5,b.minY()+1,b.minZ()+1.5);var start=cow.position();cow.move(MoverType.SELF,new Vec3(10000,0,0));
        h.assertTrue(cow.getBoundingBox().maxX<=b.high(0)+1e-6,"High speed escaped");
        cow.teleportTo(b.high(0)+2,cow.getY(),cow.getZ());h.assertTrue(cow.getX()<b.high(0),"Entity teleport escaped");
        h.assertTrue(!PartitionCollision.teleportAllowed(p,new Vec3(b.high(0)+2,p.getY(),p.getZ())),"Player teleport allowed");
        cow.noPhysics=true;cow.move(MoverType.SELF,new Vec3(10,0,0));h.assertTrue(cow.getX()>b.high(0),"noPhysics not exempt");h.succeed();
    }finally{CastChains.release(c,owner);}}
    @GameTest(template="trickster_stock_test")
    public static void cutEntitiesEscapeOnceThenCollide(GameTestHelper h){var p=player(h);var b=box(p,0,2);var cow=h.spawn(EntityType.COW,new BlockPos(1,1,1));cow.setPos(b.minX(),b.minY()+.2,b.minZ()+1.5);Object owner=new Object();var c=CastChains.attach(new CastChain(),p,owner);try {
        h.assertTrue(PartitionManager.create(p,c,b),"Cut creation rejected");h.assertTrue(PartitionCollision.clip(cow,new Vec3(-2,0,0),cow.getBoundingBox()).x==-2,"Cut entity cannot escape");
        cow.move(MoverType.SELF,new Vec3(-2,0,0));h.assertTrue(PartitionCollision.clip(cow,new Vec3(100,0,0),cow.getBoundingBox()).x<2,"Escape exemption renewed");h.succeed();
    }finally{CastChains.release(c,owner);}}
    @GameTest(template="trickster_stock_test")
    public static void distanceFeesFailBeforeNativeItemAccessAndIgnoreInversion(GameTestHelper h){var p=player(h);Object owner=new Object();var c=CastChains.attach(new CastChain(),p,owner);try(var scope=CastChains.enter(c)) {
        h.assertTrue(PartitionManager.create(p,c,box(p,0,1)),"Create failed");c.toggle();var context=ctx(p);long balance=BatteryAnima.available(p);boolean failed=false;
        try{dev.enjarai.trickster.spell.trick.Tricks.SUMMON_ARROW.activate(context,List.of(vector(p.position().add(4,0,0))));}catch(dev.enjarai.trickster.spell.exception.blunder.WrappedBlunder e){failed=e.blunder instanceof PartitionFees.InfiniteDistanceBlunder;}
        h.assertTrue(failed,"Arrow did not report infinite cost before missing-item check");h.assertTrue(BatteryAnima.available(p)==balance,"Infinite fee debited Anima");h.succeed();
    }finally{CastChains.release(c,owner);}}

    @GameTest(template="trickster_stock_test")
    public static void sentinelTrueNamesAndPlayerlessEnvironments(GameTestHelper h) throws Exception {
        var p=player(h);var other=player(h);Object owner=new Object();var c=CastChains.attach(new CastChain(),p,owner);
        var field=at.petrak.hexcasting.api.mod.HexConfig.server().getClass().getDeclaredField("doesTrueNameHaveAmbit");field.setAccessible(true);
        var config=(net.neoforged.neoforge.common.ModConfigSpec.BooleanValue)field.get(null);boolean previous=config.get();
        try {
            h.assertTrue(PartitionManager.create(p,c,box(p,0,1)),"Create failed");var env=new StaffCastEnv(p,InteractionHand.MAIN_HAND);var outside=p.position().add(4,0,0);other.setPos(outside);
            at.petrak.hexcasting.xplat.IXplatAbstractions.INSTANCE.setSentinel(p,new at.petrak.hexcasting.api.player.Sentinel(true,outside,h.getLevel().dimension()));
            h.assertTrue(!env.isVecInRange(outside),"Finite sentinel crossed partition");
            config.set(true);h.assertTrue(env.isEntityInRange(other),"True-name exemption was overridden");config.set(false);h.assertTrue(!env.isEntityInRange(other),"True name ignored disabled exemption");
            h.setBlock(BlockPos.ZERO,dev.enjarai.trickster.block.ModBlocks.SPELL_CONSTRUCT);
            var construct=new com.iridium126.createmanaindustry.compat.trickster.construct.ConstructCastEnv(h.getLevel(),h.getBlockEntity(BlockPos.ZERO));
            h.assertTrue(construct.getCastingEntity()==null&&!construct.isVecInRange(outside),"Playerless environment crossed boundary");h.succeed();
        }finally{config.set(previous);at.petrak.hexcasting.xplat.IXplatAbstractions.INSTANCE.setSentinel(p,null);CastChains.release(c,owner);}
    }
    @GameTest(template="trickster_stock_test")
    public static void creationImmediatelyRefreshesSharedFate(GameTestHelper h) {
        var p = player(h);
        Object owner = new Object();
        var c = CastChains.attach(new CastChain(), p, owner);
        var target = h.spawn(EntityType.COW, new BlockPos(1, 1, 1));
        target.setPos(p.position().add(4, 0, 0));
        // Random template placement can put the four-block endpoint in a
        // neighbouring chunk. Native EntityFragment requires entity ticking.
        var chunk = target.chunkPosition();
        boolean alreadyForced = h.getLevel().getForcedChunks().contains(chunk.toLong());
        h.getLevel().setChunkForced(chunk.x, chunk.z, true);
        h.startSequence().thenWaitUntil(() -> h.assertTrue(
                dev.enjarai.trickster.spell.fragment.EntityFragment.isValidEntity(target),
                "Shared Fate fixture is waiting for its target's entity-ticking chunk"))
                .thenExecute(() -> {
                    try {
                        com.iridium126.createmanaindustry.compat.trickster.sharedfate.SharedFateManager.bind(p, target, c, 32000);
                        h.assertTrue(c.fateTargets().size() == 1, "Initial binding failed");
                        h.assertTrue(PartitionManager.create(p, c, box(p, 0, 1)), "Create failed");
                        h.assertTrue(c.fateTargets().isEmpty(), "Cross-partition fate retained until next tick");
                    } finally {
                        CastChains.release(c, owner);
                        h.getLevel().setChunkForced(chunk.x, chunk.z, alreadyForced);
                    }
                }).thenSucceed();
    }
    @GameTest(template="trickster_stock_test")
    public static void savedExecutorRetainsAllEffectsAndConflictDropsOnlyPartition(GameTestHelper h){
        var p=player(h);Object owner=new Object(),otherOwner=new Object();var c=CastChains.attach(new CastChain(),p,owner);var d=CastChains.attach(new CastChain(),p,otherOwner);
        try {
            h.assertTrue(PartitionManager.create(p,c,box(p,0,1)),"Create failed");var saved=c.partition();c.toggle();var target=UUID.randomUUID();c.addFateTarget(target);
            var executor=new dev.enjarai.trickster.spell.execution.executor.DefaultSpellExecutor(new SpellPart(new NumberFragment(1)),List.of());
            ((ExecutorChainAccess)(Object)executor).cmi$executorChain(c);
            h.assertTrue(executor.type()==GreatTricks.PARTITION_EXECUTOR,"Wrong saved executor type");
            var serializer=io.wispforest.endec.format.edm.EdmSerializer.of();
            SpellExecutor.INTERNAL_ENDEC.encode(io.wispforest.endec.SerializationContext.empty(),serializer,executor);
            var restored=SpellExecutor.INTERNAL_ENDEC.decode(io.wispforest.endec.SerializationContext.empty(),io.wispforest.endec.format.edm.EdmDeserializer.of(serializer.result()));
            var copy=((ExecutorChainAccess)(Object)restored).cmi$executorChain();
            h.assertTrue(copy.id().equals(c.id())&&copy.inverted()&&copy.fateTargets().equals(List.of(target))&&copy.partition().equals(saved),"Executor lost saved effects");
            PartitionManager.suspend(c);h.assertTrue(c.partition().equals(saved)&&!PartitionManager.active(p.level()),"Suspension lost saved domain or retained runtime");
            long balance=BatteryAnima.available(p);PartitionManager.resume(c,p);h.assertTrue(PartitionManager.active(p.level())&&BatteryAnima.available(p)==balance,"Resume charged again");
            PartitionManager.suspend(c);h.assertTrue(PartitionManager.create(p,d,box(p,0,1)),"Conflict fixture failed");PartitionManager.resume(c,p);
            h.assertTrue(c.partition()==null&&c.inverted()&&c.fateTargets().equals(List.of(target)),"Conflict damaged other effects");h.succeed();
        }finally{CastChains.release(c,owner);CastChains.release(d,otherOwner);}
    }
    @GameTest(template="trickster_stock_test")
    public static void vehiclesPassengersProjectilesAndExemptIds(GameTestHelper h){
        var p=player(h);Object owner=new Object();var c=CastChains.attach(new CastChain(),p,owner);var previous=com.iridium126.createmanaindustry.infrastructure.config.ServerConfig.partitionMovementExemptEntities;
        try {
            var b=box(p,0,3);h.assertTrue(PartitionManager.create(p,c,b),"Create failed");
            var boat=h.spawn(EntityType.BOAT,new BlockPos(1,1,1));boat.setPos(b.minX()+2,b.minY()+1,b.minZ()+2);
            var passenger=h.spawn(EntityType.COW,new BlockPos(1,1,1));passenger.setPos(boat.position());passenger.startRiding(boat,true);
            h.assertTrue(PartitionCollision.clip(boat,new Vec3(100,0,0),boat.getBoundingBox()).x<2,"Vehicle group escaped");
            com.iridium126.createmanaindustry.infrastructure.config.ServerConfig.partitionMovementExemptEntities=List.of("minecraft:boat");
            h.assertTrue(PartitionCollision.exempt(boat)&&PartitionCollision.exempt(passenger),"Exempt vehicle did not carry passenger exemption");passenger.stopRiding();h.assertTrue(!PartitionCollision.exempt(passenger),"Dismount retained exemption");
            var arrow=h.spawn(EntityType.ARROW,new BlockPos(1,1,1));arrow.setPos(b.minX()+2,b.minY()+1,b.minZ()+2);arrow.tickCount=2;arrow.setPos(b.high(0)+100,arrow.getY(),arrow.getZ());
            h.assertTrue(arrow.getBoundingBox().maxX<=b.high(0)+1e-6,"Direct projectile integration crossed barrier");
            arrow.tickCount=0;arrow.setPos(b.minX()-2,b.minY()+1,b.minZ()+2);arrow.tickCount=2;
            arrow.teleportTo(b.high(0)+100,arrow.getY(),arrow.getZ());h.assertTrue(arrow.getX()>b.high(0),"Outside-to-outside projectile teleport was treated as travel");
            var outside=h.spawn(EntityType.COW,new BlockPos(1,1,1));outside.setPos(b.minX()-2,b.minY()+1,b.minZ()-2);
            var diagonal=new Vec3(100,0,100);h.assertTrue(PartitionCollision.clip(outside,diagonal,outside.getBoundingBox()).subtract(diagonal).lengthSqr()>1,"Diagonal sweep tunneled through opposite corners");h.succeed();
        }finally{com.iridium126.createmanaindustry.infrastructure.config.ServerConfig.partitionMovementExemptEntities=previous;CastChains.release(c,owner);}
    }
}
