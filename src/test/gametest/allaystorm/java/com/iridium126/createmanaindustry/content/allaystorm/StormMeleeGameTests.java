package com.iridium126.createmanaindustry.content.allaystorm;

import com.iridium126.createmanaindustry.content.allaystorm.network.ServerboundStormHitPacket;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.CriticalHitEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class StormMeleeGameTests {
    private static final class TestPlayer extends FakePlayer {
        int damageStatCalls, damageStat;
        TestPlayer(ServerLevel level) { super(level, new GameProfile(UUID.randomUUID(), "StormTest")); }
        @Override public void awardStat(Stat<?> stat, int amount) {
            if (stat.equals(Stats.CUSTOM.get(Stats.DAMAGE_DEALT))) { damageStatCalls++; damageStat += amount; }
        }
    }
    private record Fixture(TestPlayer player, AllayStormData data, StormIdentity identity, StormMelee.Attempts attempts, Vec3 contact) {
        ServerboundStormHitPacket packet(long sample) { return new ServerboundStormHitPacket(identity.dimension(),identity.epoch(),0,sample,contact.x,contact.y,contact.z); }
        StormMelee.Result hit() { return StormMelee.attack(player,data,identity,packet(player.serverLevel().getGameTime()),attempts); }
        void record() { Vec3 eye=player.getEyePosition();attempts.eyes.record(player.serverLevel().getGameTime(),eye.x,eye.y,eye.z); }
    }
    private static void charge(Player player,int ticks) {
        // FakePlayer intentionally does not tick. Set the vanilla ticker in this test fixture only.
        try { var field=net.minecraft.world.entity.LivingEntity.class.getDeclaredField("attackStrengthTicker");field.setAccessible(true);field.setInt(player,ticks); }
        catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
    private static Fixture fixture(GameTestHelper h,ItemStack weapon) {
        var level=h.getLevel();var player=new TestPlayer(level);player.setGameMode(GameType.SURVIVAL);
        player.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(1,2,1))));player.setOnGround(true);
        player.setItemInHand(InteractionHand.MAIN_HAND,weapon);
        weapon.getAttributeModifiers().forEach(EquipmentSlot.MAINHAND,(attribute,modifier)->{
            var instance=player.getAttribute(attribute);if(instance!=null)instance.addTransientModifier(modifier);
        });
        charge(player,40);
        var data=new AllayStormData();data.active=true;data.count=4;data.createdAtGameTime=level.getGameTime();data.stormSeed=123;
        var f=new Fixture(player,data,StormIdentity.of(level,data),new StormMelee.Attempts(),player.getEyePosition().add(2,0,0));
        f.record();return f;
    }
    private static StormMelee.Result landed(GameTestHelper h,Fixture f) {
        var result=f.hit();h.assertTrue(result!=null,"Expected a valid candidate to land");return result;
    }
    @GameTest(template="storm_combat_test",timeoutTicks=40)
    public static void bareHandSwordCooldownAndExactlyOnceResources(GameTestHelper h) {
        var bare=fixture(h,ItemStack.EMPTY);h.assertTrue(Math.abs(landed(h,bare).damage()-1)<.001,"Bare hand forged excess damage");
        var f=fixture(h,new ItemStack(Items.DIAMOND_SWORD));float beforeFood=f.player.getFoodData().getExhaustionLevel();
        var first=landed(h,f);h.assertTrue(Math.abs(first.damage()-7)<.001,"Sword attributes did not reach server calculation");
        h.assertTrue(f.player.getMainHandItem().getDamageValue()==1,"Sword durability was not consumed once");
        h.assertTrue(f.player.damageStatCalls==1&&f.player.damageStat==70,"Damage stats were not awarded once");
        h.assertTrue(Math.abs(f.player.getFoodData().getExhaustionLevel()-beforeFood-.1)<.001,"Exhaustion was not applied once");
        h.assertTrue(f.hit()==null,"Same tick duplicate was accepted");
        var replay=f.packet(h.getLevel().getGameTime());
        h.runAfterDelay(1,()->{
            h.assertTrue(StormMelee.attack(f.player,f.data,f.identity,replay,f.attempts)==null,"Old sample replay was accepted next tick");
            f.record();var weak=landed(h,f);
            h.assertTrue(weak.damage()<first.damage()*.3f,"Rapid attack bypassed vanilla cooldown scaling");
            h.assertTrue(f.player.getMainHandItem().getDamageValue()==2&&f.player.damageStatCalls==2,"Replay duplicated combat side effects");
            h.succeed();
        });
    }
    @GameTest(template="storm_combat_test")
    public static void enchantmentCriticalHookAndSprintKnockback(GameTestHelper h) {
        var sword=new ItemStack(Items.DIAMOND_SWORD);
        var registry=h.getLevel().registryAccess().registryOrThrow(Registries.ENCHANTMENT);
        sword.enchant(registry.getHolderOrThrow(Enchantments.SHARPNESS),3);
        sword.enchant(registry.getHolderOrThrow(Enchantments.KNOCKBACK),2);
        var sprint=fixture(h,sword);sprint.player.setSprinting(true);sprint.player.setDeltaMovement(1,0,1);
        var result=landed(h,sprint);
        h.assertTrue(result.damage()>7&&result.magic(),"Server enchantment damage missing");
        h.assertTrue(Math.hypot(result.kbX(),result.kbZ())>1,"Sprint/enchantment knockback missing");
        h.assertTrue(!sprint.player.isSprinting()&&Math.abs(sprint.player.getDeltaMovement().x-.6)<.001,"Attacker slowdown missing");

        var f=fixture(h,new ItemStack(Items.DIAMOND_SWORD));f.player.setOnGround(false);f.player.fallDistance=1;
        int[] events={0};Consumer<CriticalHitEvent> listener=e->{if(e.getEntity()==f.player){events[0]++;e.setCriticalHit(true);e.setDamageMultiplier(2);}};
        NeoForge.EVENT_BUS.addListener(listener);
        try {var crit=landed(h,f);h.assertTrue(crit.crit()&&Math.abs(crit.damage()-14)<.001,"NeoForge critical multiplier was ignored");f.hit();h.assertTrue(events[0]==1,"Duplicate request re-fired critical event");}
        finally {NeoForge.EVENT_BUS.unregister(listener);}
        h.succeed();
    }
    @GameTest(template="storm_combat_test")
    public static void maceSmashAndWindBurstExecuteBeforeFallReset(GameTestHelper h) {
        var mace=new ItemStack(Items.MACE);
        mace.enchant(h.getLevel().registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(Enchantments.WIND_BURST),3);
        var original=fixture(h,mace);
        // Wind Burst triggers buttons: keep its explosion clear of GameTest's command-block reset controls.
        original.player.setPos(original.player.position().add(0,20,0));
        var f=new Fixture(original.player,original.data,original.identity,new StormMelee.Attempts(),original.player.getEyePosition().add(2,0,0));
        f.record();f.player.setOnGround(false);f.player.fallDistance=100;
        f.player.setDeltaMovement(0,-1,0);
        var result=landed(h,f);
        h.assertTrue(result.damage()>63.75&&result.died(),"Mace damage was capped to old network byte range");
        h.assertTrue(f.player.getDeltaMovement().y>0,"Wind Burst post-attack enchantment did not launch attacker");
        h.assertTrue(f.player.fallDistance==0&&mace.getDamageValue()==1,"Mace postHurtEnemy ordering/durability incorrect");
        Vec3 velocity=f.player.getDeltaMovement();h.assertTrue(f.hit()==null&&velocity.equals(f.player.getDeltaMovement()),"Death/replay fired enchantment twice");
        h.succeed();
    }
    @GameTest(template="storm_combat_test")
    public static void invalidCandidatesAndWallsHaveNoCombatSideEffects(GameTestHelper h) {
        var f=fixture(h,new ItemStack(Items.DIAMOND_SWORD));long now=h.getLevel().getGameTime();var p=f.packet(now);
        var invalid=new ServerboundStormHitPacket[]{
            new ServerboundStormHitPacket(ResourceLocation.parse("minecraft:the_nether"),p.epoch(),0,now,p.x(),p.y(),p.z()),
            new ServerboundStormHitPacket(p.dimension(),p.epoch()+1,0,now,p.x(),p.y(),p.z()),
            new ServerboundStormHitPacket(p.dimension(),p.epoch(),-1,now,p.x(),p.y(),p.z()),
            new ServerboundStormHitPacket(p.dimension(),p.epoch(),99,now,p.x(),p.y(),p.z()),
            new ServerboundStormHitPacket(p.dimension(),p.epoch(),0,now-41,p.x(),p.y(),p.z()),
            new ServerboundStormHitPacket(p.dimension(),p.epoch(),0,now+3,p.x(),p.y(),p.z()),
            new ServerboundStormHitPacket(p.dimension(),p.epoch(),0,now,Double.NaN,p.y(),p.z()),
            new ServerboundStormHitPacket(p.dimension(),p.epoch(),0,now,p.x()+20,p.y(),p.z())};
        float food=f.player.getFoodData().getExhaustionLevel();
        for(var packet:invalid)h.assertTrue(StormMelee.attack(f.player,f.data,f.identity,packet,f.attempts)==null,"Invalid candidate accepted: "+packet);
        f.data.dead.set(0);h.assertTrue(f.hit()==null,"Dead member accepted attack");f.data.dead.clear(0);
        BlockPos wall=BlockPos.containing(f.player.getEyePosition().add(1,0,0));
        h.getLevel().setBlockAndUpdate(wall,Blocks.STONE.defaultBlockState());
        h.assertTrue(f.hit()==null,"Melee passed through a solid wall");
        h.assertTrue(f.player.getMainHandItem().getDamageValue()==0&&f.player.damageStatCalls==0&&f.data.hp.isEmpty(),"Rejected attack changed durability, stats or HP");
        h.assertTrue(f.player.getFoodData().getExhaustionLevel()==food&&f.player.getAttackStrengthScale(.5f)==1,"Rejected attack changed exhaustion/cooldown");
        h.getLevel().setBlockAndUpdate(wall,Blocks.AIR.defaultBlockState());landed(h,f);h.succeed();
    }
    @GameTest(template="storm_combat_test")
    public static void canceledAttackEventDoesNotConsumeResources(GameTestHelper h) {
        var f=fixture(h,new ItemStack(Items.DIAMOND_SWORD));
        Consumer<AttackEntityEvent> listener=e->{if(e.getEntity()==f.player)e.setCanceled(true);};
        NeoForge.EVENT_BUS.addListener(listener);
        try {h.assertTrue(f.hit()==null,"Canceled event landed");h.assertTrue(f.data.hp.isEmpty()&&f.player.getMainHandItem().getDamageValue()==0&&f.player.damageStatCalls==0,"Canceled event consumed combat resources");}
        finally {NeoForge.EVENT_BUS.unregister(listener);}
        h.succeed();
    }
    @GameTest(template="storm_combat_test")
    public static void delayedCandidateUsesHistoricalEye(GameTestHelper h) {
        var f=fixture(h,new ItemStack(Items.DIAMOND_SWORD));long sample=h.getLevel().getGameTime();var packet=f.packet(sample);
        h.runAfterDelay(4,()->{
            f.player.setPos(f.player.position().add(20,0,0));
            var result=StormMelee.attack(f.player,f.data,f.identity,packet,f.attempts);
            h.assertTrue(result!=null,"Valid delayed attack was checked against arrival position");h.succeed();
        });
    }
}
