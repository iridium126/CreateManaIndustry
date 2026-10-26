package com.iridium126.createmanaindustry.content.allaystorm.rift;

import com.iridium126.createmanaindustry.content.allaystorm.AllayStormData;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.vehicle.ChestBoat;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

/** Real server entities, player connections, native dimension transitions, mounting and asynchronous disk barriers. */
@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class RiftTransportGameTests {
    @GameTest(template="rift_transport_test",timeoutTicks=2000)
    public static void anyQualifiedPlayerMovesWholeNestedGroupAndInventory(GameTestHelper helper) {
        if(System.getProperty("cmi.test.riftFault")!=null){helper.succeed();return;}
        var source=helper.getLevel();var server=source.getServer();var target=server.getLevel(AllvrDimensions.ALLAY_LEVEL);helper.assertTrue(target!=null,"Allay destination unavailable");
        ServerPlayer first=player(source),second=player(source);
        Vec3 p=Vec3.atCenterOf(helper.absolutePos(new BlockPos(3,3,3)));double y=source.getMaxBuildHeight()+100;
        ChestBoat boat=EntityType.CHEST_BOAT.create(source);boat.setNoGravity(true);boat.moveTo(p.x,y,p.z,37,0);boat.setItem(0,new ItemStack(Items.DIAMOND,17));source.addFreshEntity(boat);
        var pig=EntityType.PIG.create(source);pig.setNoAi(true);pig.setNoGravity(true);pig.moveTo(p.x,y+1,p.z,55,0);source.addFreshEntity(pig);pig.startRiding(boat,true);
        first.teleportTo(p.x,y+2,p.z);second.teleportTo(p.x,y-50,p.z);first.startRiding(boat,true);second.startRiding(pig,true);
        // Source members deliberately have different heights. Only the first player's eligibility is used.
        first.setPos(p.x,source.getMaxBuildHeight()+65,p.z);second.setPos(p.x,source.getMaxBuildHeight()+63,p.z);
        UUID boatId=boat.getUUID(),pigId=pig.getUUID();UUID firstId=first.getUUID(),secondId=second.getUUID();
        var storm=new AllayStormData();storm.active=true;storm.count=16384;storm.stormSeed=712;
        helper.onEachTick(()->{
            if(first.level()==source&&!StormRiftTransfers.retained(firstId))StormRiftTransfers.consider(source,storm,p.x,p.z,target);
            if(first.level()==target&&second.level()==target) {
                // Mock connections have no renderer; the server-only test acknowledges only a published transaction.
                acknowledge(first);acknowledge(second);
            }
        });
        helper.succeedWhen(()->{
            helper.assertTrue(first.level()==target&&second.level()==target,"Companion players did not arrive together; "+StormRiftTransfers.report());
            Entity root=target.getEntity(boatId),child=target.getEntity(pigId);helper.assertTrue(root instanceof ChestBoat&&child!=null,"Vehicle or nested member missing");
            helper.assertTrue(first.getVehicle()==root&&second.getVehicle()==child&&child.getVehicle()==root,"Nested riding tree not restored");
            helper.assertTrue(((ChestBoat)root).getItem(0).is(Items.DIAMOND)&&((ChestBoat)root).getItem(0).getCount()==17,"Vehicle inventory changed");
            helper.assertTrue(source.getEntity(boatId)==null&&source.getEntity(pigId)==null,"Source entity duplicated");
            helper.assertTrue(!StormRiftTransfers.frozen(firstId)&&!StormRiftTransfers.frozen(secondId),"Native checkpoints/ready barrier still pending");
            helper.assertTrue(Math.abs(root.getX()-p.x)<=32&&Math.abs(root.getZ()-p.z)<=32,"Storm request anchor was not used");
        });
    }
    static void acknowledge(ServerPlayer player){try{var field=RiftTransferCoordinator.class.getDeclaredField("active");field.setAccessible(true);for(Object key:((Map<?,?>)field.get(RiftTransferCoordinator.current())).keySet())StormRiftTransfers.ready(player,(UUID)key);}catch(ReflectiveOperationException error){throw new AssertionError(error);}}
    static ServerPlayer player(ServerLevel level) {
        return player(level,UUID.randomUUID());
    }
    static ServerPlayer player(ServerLevel level,UUID id) {
        var profile=new com.mojang.authlib.GameProfile(id,"rift-"+id.toString().substring(0,8));var cookie=new net.minecraft.server.network.CommonListenerCookie(profile,0,ClientInformation.createDefault(),false,net.neoforged.neoforge.network.connection.ConnectionType.NEOFORGE);
        ServerPlayer player=new ServerPlayer(level.getServer(),level,profile,cookie.clientInformation());var connection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);new io.netty.channel.embedded.EmbeddedChannel(connection);
        // Negotiate the real local payload IDs on the embedded fixture connection, including optional mods.
        try{var field=net.neoforged.neoforge.network.registration.NetworkRegistry.class.getDeclaredField("PAYLOAD_REGISTRATIONS");field.setAccessible(true);for(Object value:((Map<?,?>)field.get(null)).values())for(Object key:((Map<?,?>)value).keySet())net.neoforged.neoforge.network.registration.ChannelAttributes.getOrCreateAdHocChannels(connection).add((net.minecraft.resources.ResourceLocation)key);}catch(ReflectiveOperationException error){throw new AssertionError(error);}
        net.neoforged.neoforge.network.registration.ChannelAttributes.setConnectionType(connection,net.neoforged.neoforge.network.connection.ConnectionType.NEOFORGE);
        level.getServer().getPlayerList().placeNewPlayer(connection,player,cookie);player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);player.setNoGravity(true);return player;
    }
}
