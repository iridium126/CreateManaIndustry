package com.iridium126.createmanaindustry.content.allaystorm.rift;

import com.iridium126.createmanaindustry.content.allaystorm.AllayStormData;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.vehicle.ChestBoat;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.*;
import net.neoforged.neoforge.gametest.*;
import java.util.function.Consumer;

/** Failure injection uses real cancellable engine events, rather than a mock transaction backend. */
@GameTestHolder("createmanaindustry") @PrefixGameTestTemplate(false)
public final class RiftFailureGameTests {
    @GameTest(template="rift_transport_test",timeoutTicks=2000)
    public static void oneCompanionTravelCancellationMovesNobody(GameTestHelper helper){run(helper,false);}
    @GameTest(template="rift_transport_test",timeoutTicks=2000)
    public static void nativeTargetJoinCancellationCompensatesPreviouslyMovedMembers(GameTestHelper helper){run(helper,true);}
    private static void run(GameTestHelper helper,boolean partial) {
        if(System.getProperty("cmi.test.riftFault")!=null){helper.succeed();return;}
        var source=helper.getLevel();var target=source.getServer().getLevel(AllvrDimensions.ALLAY_LEVEL);var player=RiftTransportGameTests.player(source);
        Vec3 pos=Vec3.atCenterOf(helper.absolutePos(new BlockPos(3,3,3))).add(0,source.getMaxBuildHeight()+170,0);
        var boat=EntityType.CHEST_BOAT.create(source);boat.setNoGravity(true);boat.moveTo(pos.x,pos.y,pos.z,27,0);boat.setItem(0,new ItemStack(Items.DIAMOND,17));source.addFreshEntity(boat);
        var pig=EntityType.PIG.create(source);pig.setNoGravity(true);pig.setNoAi(true);pig.moveTo(pos.x,pos.y+1,pos.z,0,0);source.addFreshEntity(pig);pig.startRiding(boat,true);player.startRiding(pig,true);player.setPos(pos.x,pos.y+2,pos.z);
        var boatId=boat.getUUID();var pigId=pig.getUUID();boolean[] fired={false},requested={false};
        Consumer<EntityTravelToDimensionEvent> travel=event->{if(!partial&&event.getEntity().getUUID().equals(pigId)&&event.getDimension()==target.dimension()){event.setCanceled(true);fired[0]=true;}};
        Consumer<EntityJoinLevelEvent> join=event->{if(partial&&event.getEntity().getUUID().equals(pigId)&&event.getLevel()==target){event.setCanceled(true);fired[0]=true;}};
        NeoForge.EVENT_BUS.addListener(travel);NeoForge.EVENT_BUS.addListener(join);
        var storm=new AllayStormData();storm.active=true;storm.count=16384;storm.stormSeed=partial?751:750;
        helper.onEachTick(()->{if(!requested[0]&&StormRiftTransfers.report().contains("rift-transfer=active")){StormRiftTransfers.consider(source,storm,pos.x,pos.z,target);if(StormRiftTransfers.retained(player.getUUID()))requested[0]=true;}});
        helper.succeedWhen(()->{
            helper.assertTrue(fired[0]&&!StormRiftTransfers.retained(player.getUUID()),"Expected cancellation/compensation still pending: "+StormRiftTransfers.report());
            Entity restoredBoat=source.getEntity(boatId),restoredPig=source.getEntity(pigId);helper.assertTrue(restoredBoat instanceof ChestBoat&&restoredPig!=null&&player.level()==source,"Compensation did not restore the whole source group");
            helper.assertTrue(player.getVehicle()==restoredPig&&restoredPig.getVehicle()==restoredBoat,"Compensation lost nested mounts");helper.assertTrue(((ChestBoat)restoredBoat).getItem(0).is(Items.DIAMOND)&&((ChestBoat)restoredBoat).getItem(0).getCount()==17,"Compensation changed inventory");
            helper.assertTrue(target.getEntity(boatId)==null&&target.getEntity(pigId)==null,"Compensation left target copies");helper.assertTrue(!StormRiftTransfers.frozen(player.getUUID()),"Compensation leaked lock");
            NeoForge.EVENT_BUS.unregister(travel);NeoForge.EVENT_BUS.unregister(join);
        });
    }
}
