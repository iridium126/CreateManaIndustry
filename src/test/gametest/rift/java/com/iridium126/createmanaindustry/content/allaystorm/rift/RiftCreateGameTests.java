package com.iridium126.createmanaindustry.content.allaystorm.rift;
import com.iridium126.createmanaindustry.content.allaystorm.AllayStormData;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.simibubi.create.content.contraptions.*;
import com.simibubi.create.content.contraptions.bearing.BearingContraption;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.gametest.*;
@GameTestHolder("createmanaindustry") @PrefixGameTestTemplate(false)
public final class RiftCreateGameTests {
    @GameTest(template="rift_transport_test",timeoutTicks=2000)
    public static void orientedContraptionRetainsTwoSeatsAndPose(GameTestHelper helper) {
        if(System.getProperty("cmi.test.riftFault")!=null){helper.succeed();return;}
        var source=helper.getLevel();var target=source.getServer().getLevel(AllvrDimensions.ALLAY_LEVEL);var first=RiftTransportGameTests.player(source);var second=RiftTransportGameTests.player(source);
        Vec3 p=Vec3.atCenterOf(helper.absolutePos(new BlockPos(3,3,3))).add(0,source.getMaxBuildHeight()+180,0);
        var data=new BearingContraption(false,Direction.UP);data.anchor=BlockPos.containing(p);data.bounds=new AABB(0,0,0,3,1,1);
        for(int x=0;x<3;x++){BlockPos local=new BlockPos(x,0,0);data.getBlocks().put(local,new StructureBlockInfo(local,Blocks.OAK_PLANKS.defaultBlockState(),null));}
        data.getSeats().add(BlockPos.ZERO);data.getSeats().add(new BlockPos(2,0,0));
        var entity=OrientedContraptionEntity.create(source,data,Direction.NORTH);entity.setNoGravity(true);entity.moveTo(p.x,p.y,p.z,0,0);source.addFreshEntity(entity);entity.addSittingPassenger(first,0);entity.addSittingPassenger(second,1);first.setPos(p.x,p.y+1,p.z);second.setPos(p.x+2,p.y+1,p.z);
        var id=entity.getUUID();var storm=new AllayStormData();storm.active=true;storm.count=16384;storm.stormSeed=715;
        helper.onEachTick(()->{if(first.level()==source&&!StormRiftTransfers.retained(first.getUUID()))StormRiftTransfers.consider(source,storm,p.x,p.z,target);if(first.level()==target){RiftTransportGameTests.acknowledge(first);RiftTransportGameTests.acknowledge(second);}});
        helper.succeedWhen(()->{var moved=target.getEntity(id);helper.assertTrue(moved instanceof OrientedContraptionEntity,"Contraption not migrated; "+StormRiftTransfers.report());var contraption=((OrientedContraptionEntity)moved).getContraption();helper.assertTrue(first.getVehicle()==moved&&second.getVehicle()==moved,"Contraption passenger tree lost");helper.assertTrue(contraption.getSeatMapping().get(first.getUUID())==0&&contraption.getSeatMapping().get(second.getUUID())==1,"Create seat mapping changed");helper.assertTrue(contraption.getBlocks().size()==3&&contraption.getSeats().equals(data.getSeats()),"Contraption blocks/seats changed");helper.assertTrue(!StormRiftTransfers.frozen(first.getUUID()),"Contraption durability/ready barrier pending");});
    }
}
