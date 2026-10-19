package com.iridium126.createmanaindustry.content.allaystorm;

import com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormColumnPacket;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import com.mojang.authlib.GameProfile;
import java.util.*;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class StarfallColumnGameTests {
    private record Fixture(StarfallColumns columns,AllayStormData data,StormIdentity identity,
                           ClientboundStormColumnPacket packet,FakePlayer trigger,FakePlayer other,Vec3 contact,BitSet reserved) {
        boolean hit(FakePlayer player,int member,float sample,Vec3 point,long epoch){return columns.contact(player,data,identity,packet.id(),epoch,sample,member,point);}
        boolean hit(FakePlayer player){return hit(player,0,StarfallColumns.clock(player.level().getGameTime()),contact,packet.epoch());}
        void tick(List<net.minecraft.server.level.ServerPlayer> players){columns.tick(trigger.serverLevel(),data,packet.centerX(),packet.centerZ(),players,reserved,()->999,p->{});}
    }
    private static final class CombatPlayer extends FakePlayer {
        CombatPlayer(ServerLevel level,String name){
            super(level,new GameProfile(UUID.randomUUID(),name));
            // FakePlayer never ticks down ServerPlayer's normal 60-tick spawn protection.
            try{var field=net.minecraft.server.level.ServerPlayer.class.getDeclaredField("spawnInvulnerableTime");field.setAccessible(true);field.setInt(this,0);}
            catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
        }
        @Override public boolean isInvulnerableTo(net.minecraft.world.damagesource.DamageSource source){return false;}
    }
    private static FakePlayer player(ServerLevel level,String name,Vec3 pos){
        var p=new CombatPlayer(level,name);p.setGameMode(GameType.SURVIVAL);p.setPos(pos);p.setOnGround(true);return p;
    }
    private static Fixture fixture(GameTestHelper h){return fixture(h,-.2f,60);}
    private static Fixture fixture(GameTestHelper h,float touchOffset,float assembleAgo){
        var level=h.getLevel();BlockPos base=h.absolutePos(new BlockPos(40,20,40));
        // A dedicated elevated platform keeps surface queries away from GameTest control blocks.
        for(int x=-14;x<=14;x++)for(int z=-14;z<=14;z++){
            level.setBlockAndUpdate(base.offset(x,0,z),Blocks.STONE.defaultBlockState());
            for(int y=1;y<8;y++)level.setBlockAndUpdate(base.offset(x,y,z),Blocks.AIR.defaultBlockState());
        }
        Vec3 center=Vec3.atBottomCenterOf(base.above()).add(0,.02,0);float now=StarfallColumns.clock(level.getGameTime());
        var data=new AllayStormData();data.active=true;data.count=64;data.createdAtGameTime=level.getGameTime();data.stormSeed=123;
        var identity=StormIdentity.of(level,data);
        var packet=new ClientboundStormColumnPacket(identity.dimension(),identity.epoch(),1,false,0,now-assembleAgo-4,now-assembleAgo,now+touchOffset,now+touchOffset+7.28f,now+40,
            (float)center.x,(float)center.y,(float)center.z,ServerConfig.stormChaseY-8,(float)center.x,(float)center.z,8,IntStream.range(0,64).toArray());
        var engine=new StarfallColumns();var reserved=new BitSet();engine.reserve(packet,reserved);
        Vec3 contact=center.add(.5,StarfallColumnRules.height(.5),0);
        var trigger=player(level,"ColumnTrigger",center.add(0,0,1));var other=player(level,"ColumnOther",center.add(1,0,0));
        var f=new Fixture(engine,data,identity,packet,trigger,other,contact,reserved);f.tick(List.of(trigger,other));return f;
    }
    @GameTest(template="storm_combat_test",timeoutTicks=40)
    public static void actualEarlyTouchdownDoesNotWaitForNominalArrival(GameTestHelper h){
        var f=fixture(h,1,60);float now=StarfallColumns.clock(h.getLevel().getGameTime());
        h.assertTrue(now<f.packet.touch(),"Early arrival fixture was late");
        h.assertTrue(f.hit(f.other),"Actual touchdown waited for nominal schedule");
        h.assertTrue(f.other.getHealth()<20,"Early bloom did not deal damage");h.succeed();
    }
    @GameTest(template="storm_combat_test",timeoutTicks=40)
    public static void clientContactDoesNotDependOnNominalClock(GameTestHelper h){
        var f=fixture(h,20,0);float now=StarfallColumns.clock(h.getLevel().getGameTime());
        h.assertTrue(StarfallColumnRules.earliestTouch(f.packet.assemble(),f.packet.gatherY(),f.packet.y(),0)>now,"Fixture can already reach ground");
        h.assertTrue(f.hit(f.other),"Trusted GPU contact was rejected by nominal time");
        h.assertTrue(f.other.getHealth()<20,"Trusted contact did not damage player");h.succeed();
    }
    @GameTest(template="storm_combat_test",timeoutTicks=40)
    public static void regionalBloomSurvivesTriggerDepartureAndDeduplicates(GameTestHelper h){
        var f=fixture(h);f.tick(List.of(f.other));
        h.assertTrue(f.columns.snapshots().size()==1,"Trigger departure cancelled regional column");
        h.assertTrue(f.hit(f.other),"Other nearby player could not receive bloom contact");
        h.assertTrue(Math.abs(f.other.getHealth()-13)<.01,"Regional hit did not use server-configured damage");
        h.assertTrue(!f.hit(f.other),"Duplicate member damaged the same player twice");
        f.tick(List.of(f.trigger,f.other));
        h.assertTrue(f.hit(f.trigger),"One player's contact consumed another player's member admission");
        h.succeed();
    }
    @GameTest(template="storm_combat_test",timeoutTicks=40)
    public static void rejectsWrongIdentityAndMemberWithoutConsumingAdmission(GameTestHelper h){
        var f=fixture(h);float now=StarfallColumns.clock(h.getLevel().getGameTime());
        h.assertTrue(!f.hit(f.other,0,now,f.contact,f.packet.epoch()+1),"Wrong epoch accepted");



        h.assertTrue(!f.hit(f.other,64,now,f.contact,f.packet.epoch()),"Non-roster member accepted");

        h.assertTrue(!f.hit(f.other,0,now,new Vec3(Double.NaN,0,0),f.packet.epoch()),"Nonfinite contact accepted");
        var obstacle=BlockPos.containing(f.contact).above();h.getLevel().setBlockAndUpdate(obstacle,Blocks.STONE.defaultBlockState());

        h.assertTrue(f.other.getHealth()==20,"Rejected candidates changed health");
        h.assertTrue(f.hit(f.other),"Rejected requests consumed admission or repeated terrain checks rejected trusted GPU contact");h.getLevel().setBlockAndUpdate(obstacle,Blocks.AIR.defaultBlockState());h.succeed();
    }
    @GameTest(template="storm_combat_test",timeoutTicks=40)
    public static void bloomRetainsVanillaArmorAndInvulnerabilityFrames(GameTestHelper h){
        var f=fixture(h);var armor=new ItemStack(Items.DIAMOND_CHESTPLATE);f.other.setItemSlot(EquipmentSlot.CHEST,armor);
        armor.getAttributeModifiers().forEach(EquipmentSlot.CHEST,(attribute,modifier)->{var instance=f.other.getAttribute(attribute);if(instance!=null)instance.addTransientModifier(modifier);});
        h.assertTrue(f.hit(f.other),"Armored bloom contact rejected");float health=f.other.getHealth();
        h.assertTrue(health>13&&health<20,"Vanilla armor did not reduce bloom damage");
        float now=StarfallColumns.clock(h.getLevel().getGameTime());
        // A distinct member is admitted independently; vanilla i-frames suppress its equal damage.
        h.assertTrue(f.hit(f.other,1,now,f.contact,f.packet.epoch()),"Second member not admitted");
        h.assertTrue(f.other.getHealth()==health,"Concurrent members bypassed vanilla i-frames");h.succeed();
    }
    @GameTest(template="storm_combat_test",timeoutTicks=40)
    public static void bloomUsesVanillaShieldAndTotem(GameTestHelper h){
        var f=fixture(h);var shield=new ItemStack(Items.SHIELD);f.other.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,shield);
        f.other.setYRot(90);f.other.setYHeadRot(90);f.other.startUsingItem(net.minecraft.world.InteractionHand.MAIN_HAND);
        try{var field=net.minecraft.world.entity.LivingEntity.class.getDeclaredField("useItemRemaining");field.setAccessible(true);field.setInt(f.other,shield.getUseDuration(f.other)-6);}
        catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
        h.assertTrue(f.other.isBlocking(),"Shield fixture was not raised");
        h.assertTrue(f.hit(f.other),"Shield contact was not admitted");h.assertTrue(f.other.getHealth()==20&&shield.getDamageValue()>0,"Vanilla shield did not block bloom");
        f.trigger.setHealth(1);f.trigger.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND,new ItemStack(Items.TOTEM_OF_UNDYING));
        h.assertTrue(f.hit(f.trigger),"Lethal bloom contact was not admitted");
        h.assertTrue(f.trigger.isAlive()&&f.trigger.getOffhandItem().isEmpty(),"Bloom bypassed vanilla totem");h.succeed();
    }
    @GameTest(template="storm_combat_test",timeoutTicks=40)
    public static void irregularGroundDoesNotAbortRegionalColumn(GameTestHelper h){
        var f=fixture(h);var uneven=BlockPos.containing(f.contact).offset(3,0,0);h.getLevel().setBlockAndUpdate(uneven.above(),Blocks.STONE.defaultBlockState());
        h.assertTrue(StarfallColumns.terrain(h.getLevel(),f.packet.x(),f.packet.z(),f.packet.gatherY(),f.packet.y()),"Rough terrain blocked column generation");
        f.tick(List.of(f.other));h.assertTrue(f.columns.snapshots().size()==1,"Terrain edits cancelled a whole column");h.succeed();
    }
    @GameTest(template="storm_combat_test",timeoutTicks=50)
    public static void schedulerHonorsTenTickOpportunitiesFrozenXZAndSpacing(GameTestHelper h){
        var f=fixture(h);f.columns.clear();f.reserved.clear();f.data.count=4096;
        var sent=new ArrayList<ClientboundStormColumnPacket>();var ids=new java.util.concurrent.atomic.AtomicInteger(200);
        var chance=new java.util.concurrent.atomic.AtomicReference<>(1.0);
        Runnable attempt=()->{
            double oldChance=ServerConfig.stormStarfallChance,oldInterval=ServerConfig.stormStarfallInterval;
            try{ServerConfig.stormStarfallChance=chance.get();ServerConfig.stormStarfallInterval=.5;
                f.columns.tick(h.getLevel(),f.data,f.packet.centerX(),f.packet.centerZ(),List.of(f.other),f.reserved,ids::incrementAndGet,sent::add);
            }finally{ServerConfig.stormStarfallChance=oldChance;ServerConfig.stormStarfallInterval=oldInterval;}
        };
        attempt.run();h.assertTrue(sent.size()==1,"First eligible opportunity did not create one column");
        var first=sent.getFirst();double originalX=f.other.getX(),originalZ=f.other.getZ();
        h.assertTrue(Math.abs(first.x()-originalX)<=1&&Math.abs(first.z()-originalZ)<=1,"Column did not freeze player XZ");
        h.runAfterDelay(9,()->{attempt.run();h.assertTrue(sent.size()==1,"Column launched before ten ticks");});
        h.runAfterDelay(10,()->{chance.set(0.0);attempt.run();h.assertTrue(sent.size()==1,"Zero chance generated a column");});
        h.runAfterDelay(19,()->{chance.set(1.0);attempt.run();h.assertTrue(sent.size()==1,"Failed draw did not consume its opportunity");});
        h.runAfterDelay(20,()->{
            attempt.run();h.assertTrue(sent.size()==2,"Ten-tick opportunity was lost");var second=sent.getLast();
            h.assertTrue(Math.hypot(first.x()-second.x(),first.z()-second.z())>=8-1e-5,"Independent columns overlapped");
            h.assertTrue(f.reserved.cardinality()==128,"Column rosters were not exclusive");
            f.other.setPos(originalX+2,f.other.getY(),originalZ);h.assertTrue(f.columns.snapshots().getFirst().x()==first.x(),"Column followed player after launch");
            h.succeed();
        });
    }
    @GameTest(template="storm_combat_test",timeoutTicks=40)
    public static void fullCapacityRotatesEligiblePlayerPriority(GameTestHelper h){
        var f=fixture(h);f.columns.clear();f.reserved.clear();f.data.count=16384;
        f.trigger.setPos(f.packet.centerX()-7,f.trigger.getY(),f.packet.centerZ());f.other.setPos(f.packet.centerX()+7,f.other.getY(),f.packet.centerZ());
        var ids=new java.util.concurrent.atomic.AtomicInteger(300);
        Runnable attempt=()->{
            double oldChance=ServerConfig.stormStarfallChance,oldInterval=ServerConfig.stormStarfallInterval;int oldCapacity=ServerConfig.stormStarfallMaxActiveColumns;
            try{ServerConfig.stormStarfallChance=1;ServerConfig.stormStarfallInterval=.5;ServerConfig.stormStarfallMaxActiveColumns=1;
                f.columns.tick(h.getLevel(),f.data,f.packet.centerX(),f.packet.centerZ(),List.of(f.trigger,f.other),f.reserved,ids::incrementAndGet,p->{});
            }finally{ServerConfig.stormStarfallChance=oldChance;ServerConfig.stormStarfallInterval=oldInterval;ServerConfig.stormStarfallMaxActiveColumns=oldCapacity;}
        };
        attempt.run();h.assertTrue(f.columns.snapshots().size()==1,"Capacity one admitted multiple columns");var first=f.columns.snapshots().getFirst();
        double nextX=Math.abs(first.x()-f.trigger.getX())<1?f.other.getX():f.trigger.getX();
        f.data.active=false;attempt.run();h.assertTrue(f.columns.snapshots().isEmpty()&&f.reserved.isEmpty(),"Abort did not release capacity and members");f.data.active=true;
        h.runAfterDelay(10,()->{attempt.run();h.assertTrue(f.columns.snapshots().size()==1,"Capacity was not reusable");
            h.assertTrue(Math.abs(f.columns.snapshots().getFirst().x()-nextX)<1,"Fixed player won consecutive capacity opportunities: first="+first.x()+", next="+f.columns.snapshots().getFirst().x()+", expected="+nextX+", players="+f.trigger.getX()+","+f.other.getX());h.succeed();});
    }
}
