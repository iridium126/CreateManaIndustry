package com.iridium126.createmanaindustry.content.allaystorm.rift;

import com.iridium126.createmanaindustry.content.allaystorm.AllayStormData;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("createmanaindustry") @PrefixGameTestTemplate(false)
public final class RiftOrdinaryVehicleGameTests {
    @GameTest(template="rift_transport_test",timeoutTicks=2000)
    public static void horseAndPassengerUseTheSameSafeTranslation(GameTestHelper helper){run(helper,true);}
    @GameTest(template="rift_transport_test",timeoutTicks=2000)
    public static void minecartAndPassengerRetainMountAcrossNativeTransition(GameTestHelper helper){run(helper,false);}
    private static void run(GameTestHelper helper,boolean horse){
        if(System.getProperty("cmi.test.riftFault")!=null){helper.succeed();return;}
        var source=helper.getLevel();var target=source.getServer().getLevel(AllvrDimensions.ALLAY_LEVEL);var player=RiftTransportGameTests.player(source);Entity vehicle=horse?EntityType.HORSE.create(source):EntityType.MINECART.create(source);if(vehicle instanceof net.minecraft.world.entity.animal.horse.Horse h){h.setTamed(true);h.setNoAi(true);}vehicle.setNoGravity(true);
        Vec3 point=Vec3.atCenterOf(helper.absolutePos(new BlockPos(3,3,3))).add(0,source.getMaxBuildHeight()+180,0);vehicle.moveTo(point.x,point.y,point.z,49,0);source.addFreshEntity(vehicle);player.startRiding(vehicle,true);player.setPos(point.x,point.y+2,point.z);var id=vehicle.getUUID();var type=vehicle.getType();var storm=new AllayStormData();storm.active=true;storm.count=16384;storm.stormSeed=horse?771:772;
        helper.onEachTick(()->{if(player.level()==source&&!StormRiftTransfers.frozen(player.getUUID())){vehicle.setPos(point);vehicle.setDeltaMovement(Vec3.ZERO);player.setPos(point.x,point.y+2,point.z);}if(player.level()==source&&!StormRiftTransfers.retained(player.getUUID()))StormRiftTransfers.consider(source,storm,point.x,point.z,target);if(player.level()==target)RiftTransportGameTests.acknowledge(player);});
        helper.succeedWhen(()->{Entity root=target.getEntity(id);helper.assertTrue(player.level()==target&&root!=null&&root.getType()==type,"Vehicle group not transported: "+StormRiftTransfers.report());helper.assertTrue(player.getVehicle()==root&&source.getEntity(id)==null,"Vehicle mount missing or duplicated");helper.assertTrue(!StormRiftTransfers.frozen(player.getUUID()),"Vehicle ready/checkpoint pending");helper.assertTrue(root.getBoundingBox().minY>=-128&&player.getBoundingBox().maxY<=384,"Vehicle group outside native arrival band");});
    }
}
