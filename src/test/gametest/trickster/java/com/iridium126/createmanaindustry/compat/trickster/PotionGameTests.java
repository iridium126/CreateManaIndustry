package com.iridium126.createmanaindustry.compat.trickster;

import com.iridium126.createmanaindustry.compat.hextrick.data.TrickIota;
import com.iridium126.createmanaindustry.compat.hextrick.conversion.FragmentConverter;

import com.iridium126.createmanaindustry.compat.trickster.anima.BatteryAnima;
import com.iridium126.createmanaindustry.compat.trickster.casting.CastChain;
import com.iridium126.createmanaindustry.compat.trickster.casting.CastChains;
import com.iridium126.createmanaindustry.compat.trickster.casting.ExecutorChainAccess;
import com.iridium126.createmanaindustry.compat.trickster.fragment.PotionFragment;
import com.iridium126.createmanaindustry.compat.trickster.greattrick.GreatPattern;
import com.iridium126.createmanaindustry.compat.trickster.greattrick.GreatTricks;

import at.petrak.hexcasting.api.casting.iota.IotaType;
import at.petrak.hexcasting.api.mod.HexConfig;
import at.petrak.hexcasting.common.lib.HexDataComponents;
import at.petrak.hexcasting.xplat.IXplatAbstractions;
import com.iridium126.createmanaindustry.*;
import com.iridium126.createmanaindustry.compat.hexcasting.*;
import com.mojang.authlib.GameProfile;
import dev.enjarai.trickster.spell.*;
import dev.enjarai.trickster.spell.execution.*;
import dev.enjarai.trickster.spell.execution.executor.DefaultSpellExecutor;
import dev.enjarai.trickster.spell.execution.source.*;
import dev.enjarai.trickster.spell.fragment.*;
import dev.enjarai.trickster.spell.trick.Tricks;
import io.wispforest.endec.SerializationContext;
import io.wispforest.endec.format.edm.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.world.effect.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.common.*;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.gametest.*;
import java.util.*;
import java.util.function.Consumer;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class PotionGameTests {
    private static ServerPlayer player(GameTestHelper helper) {
        var sink=FakePlayerFactory.get(helper.getLevel(),new GameProfile(UUID.randomUUID(),"PotionTest"));
        var player=new ServerPlayer(helper.getLevel().getServer(),helper.getLevel(),sink.getGameProfile(),ClientInformation.createDefault());
        player.connection=sink.connection;
        var pos=helper.absolutePos(BlockPos.ZERO).getCenter();player.setPos(pos);
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);player.getAbilities().invulnerable=false;
        player.getInventory().setItem(0,battery(320000));return player;
    }
    private static ItemStack battery(long balance) {
        var stack=new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("hexcasting:battery")));
        stack.set(HexDataComponents.MEDIA_MAX,640000000L);stack.set(HexDataComponents.MEDIA,12345L);
        stack.set(CMIComponents.ANIMA.get(),balance);return stack;
    }
    private static SpellContext context(ServerPlayer player) {
        return new SpellContext(new ExecutionState(List.of()),new PlayerSpellSource(player),new TickData());
    }
    private static PotionFragment speed(int duration,int amplifier) {
        return PotionFragment.fromEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED,duration,amplifier));
    }
    private static Fragment apply(SpellContext context,EntityFragment target,PotionFragment potion) {
        return GreatTricks.TINCTURE_SHAPING.activate(context,List.of(target,potion));
    }
    private static void failsFree(GameTestHelper helper,ServerPlayer player,Runnable operation) {
        long balance=BatteryAnima.available(player);boolean failed=false;
        try {operation.run();}catch(dev.enjarai.trickster.spell.exception.blunder.WrappableBlunder expected){failed=true;}
        helper.assertTrue(failed&&BatteryAnima.available(player)==balance,"Failed shaping changed Anima balance");
    }
    @GameTest(template="trickster_stock_test")
    public static void queryWorksForPlayerlessSourcesButShapingRejectsThem(GameTestHelper helper) {
        var cow=helper.spawn(EntityType.COW,new BlockPos(1,1,1));
        cow.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION,100));
        var pos=new BlockPos(0,1,0);
        helper.setBlock(pos,dev.enjarai.trickster.block.ModBlocks.SPELL_CONSTRUCT);
        var block=(dev.enjarai.trickster.block.SpellConstructBlockEntity)helper.getBlockEntity(pos);
        var source=new BlockSpellSource<>(helper.getLevel(),block.getBlockPos(),block);
        var ctx=new SpellContext(new ExecutionState(List.of()),source,new TickData());
        helper.assertTrue(CMITricks.READ_POTION_EFFECTS.run(ctx,EntityFragment.from(cow)).size()==1,"Playerless effect query failed");
        boolean failed=false;
        try{apply(ctx,EntityFragment.from(cow),speed(100,1));}catch(dev.enjarai.trickster.spell.exception.blunder.NoPlayerBlunder expected){failed=true;}
        helper.assertTrue(failed&&!cow.hasEffect(MobEffects.MOVEMENT_SPEED),"Playerless shaping applied an effect");
        helper.succeed();
    }
    @GameTest(template="trickster_stock_test")
    public static void animaReceiptUndoPreservesIndependentDebitsAndComponents(GameTestHelper helper) {
        var player=player(helper);player.getInventory().setItem(0,battery(20000));player.getInventory().setItem(1,battery(20000));
        var receipt=BatteryAnima.reserve(player,32000).orElseThrow();
        helper.assertTrue(BatteryAnima.available(player)==8000,"Reservation was not immediate");
        BatteryAnima.consume(player,4000);
        player.getInventory().getItem(0).set(HexDataComponents.MEDIA,999L);
        receipt.close();receipt.close();
        helper.assertTrue(BatteryAnima.available(player)==36000,"Undo reverted independent Anima debit or refunded twice");
        helper.assertTrue(BatteryAnima.get(player.getInventory().getItem(0))==20000
                &&BatteryAnima.get(player.getInventory().getItem(1))==16000,"Refund changed slot allocation");
        helper.assertTrue(player.getInventory().getItem(0).get(HexDataComponents.MEDIA)==999L,"Refund overwrote media");
        try(var committed=BatteryAnima.reserve(player,32000).orElseThrow()){committed.commit();}
        helper.assertTrue(BatteryAnima.available(player)==4000,"Committed receipt refunded on close");
        helper.assertTrue(BatteryAnima.reserve(player,4001).isEmpty()&&BatteryAnima.available(player)==4000,"Insufficient reserve changed balance");
        helper.succeed();
    }
    @GameTest(template="trickster_stock_test")
    public static void fullSnapshotPreservesHiddenCuresAndDefensiveCopies(GameTestHelper helper) {
        var hidden=new MobEffectInstance(MobEffects.MOVEMENT_SPEED,600,1,true,false,false);
        hidden.getCures().clear();hidden.getCures().add(EffectCure.get("cmi:hidden"));
        var effect=new MobEffectInstance(MobEffects.MOVEMENT_SPEED,40,3,true,false,true,hidden);
        effect.getCures().clear();effect.getCures().add(EffectCure.get("cmi:z"));effect.getCures().add(EffectCure.get("cmi:a"));
        var snapshot=PotionFragment.fromEffect(effect);
        var restored=(PotionFragment)Fragment.fromBytes(snapshot.toBytes());
        helper.assertTrue(restored.equals(snapshot)&&restored.hashCode()==snapshot.hashCode(),"Potion compact codec lost complete value");
        var decoded=restored.toEffect();
        helper.assertTrue(decoded.getDuration()==40&&decoded.getAmplifier()==3&&decoded.isAmbient()&&!decoded.isVisible()&&decoded.showIcon(),"Effect flags were simplified");
        var tag=(CompoundTag)MobEffectInstance.CODEC.encodeStart(NbtOps.INSTANCE,decoded).getOrThrow();
        helper.assertTrue(tag.getCompound("hidden_effect").getInt("duration")==600,"Hidden effect lost");
        helper.assertTrue(decoded.getCures().equals(effect.getCures()),"Custom cure set lost");
        effect.getCures().clear();hidden.getCures().clear();effect.update(new MobEffectInstance(MobEffects.MOVEMENT_SPEED,900,4));
        decoded.update(new MobEffectInstance(MobEffects.MOVEMENT_SPEED,800,5));
        byte[] exposed=snapshot.getBytes();exposed[0]=0;
        helper.assertTrue(snapshot.toEffect().getDuration()==40&&snapshot.toEffect().getAmplifier()==3,"Snapshot shared mutable state");
        helper.assertTrue(PotionFragment.fromEffect(snapshot.toEffect()).equals(snapshot),"Repeated cure encoding changed equality");
        var infinite=PotionFragment.fromEffect(new MobEffectInstance(MobEffects.NIGHT_VISION,-1,0));
        helper.assertTrue(((PotionFragment)Fragment.fromBytes(infinite.toBytes())).toEffect().isInfiniteDuration(),"Infinite duration lost");
        var modEffect=BuiltInRegistries.MOB_EFFECT.holders().filter(holder->!holder.key().location().getNamespace().equals("minecraft")).findFirst().orElseThrow();
        var arbitrary=PotionFragment.fromEffect(new MobEffectInstance(modEffect,120,2));
        helper.assertTrue(((PotionFragment)Fragment.fromBytes(arbitrary.toBytes())).equals(arbitrary),"Registered mod effect failed codec");
        helper.succeed();
    }
    @GameTest(template="trickster_stock_test")
    public static void queryIsOrderedFreeAndHandlesEmptyAndNonlivingEntities(GameTestHelper helper) {
        var player=player(helper);var ctx=context(player);var cow=helper.spawn(EntityType.COW,new BlockPos(1,1,1));
        cow.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION,200));cow.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED,100));
        long balance=BatteryAnima.available(player);var target=EntityFragment.from(cow);
        var values=CMITricks.READ_POTION_EFFECTS.run(ctx,target);
        helper.assertTrue(values.size()==2,"Query did not return one fragment per active effect");
        var ids=values.stream().map(p->BuiltInRegistries.MOB_EFFECT.getKey(p.toEffect().getEffect().value()).toString()).toList();
        helper.assertTrue(ids.equals(ids.stream().sorted().toList()),"Query ordering is not stable");
        helper.assertTrue(BatteryAnima.available(player)==balance&&player.getInventory().getItem(0).get(HexDataComponents.MEDIA)==12345L,"Query charged resources");
        var result=new DefaultSpellExecutor(new SpellPart(new PatternGlyph(CMITricks.READ_POTION_EFFECTS.getPattern()),List.of(new SpellPart(target))),List.of()).run(new PlayerSpellSource(player)).orElseThrow();
        helper.assertTrue(result instanceof ListFragment list&&list.fragments().size()==2,"Native Entity -> [Potion] signature failed");
        cow.removeAllEffects();helper.assertTrue(CMITricks.READ_POTION_EFFECTS.run(ctx,target).isEmpty(),"Empty effects did not return empty list");
        var item=new net.minecraft.world.entity.item.ItemEntity(helper.getLevel(),cow.getX(),cow.getY(),cow.getZ(),new ItemStack(Items.STONE));helper.getLevel().addFreshEntity(item);
        helper.assertTrue(CMITricks.READ_POTION_EFFECTS.run(ctx,EntityFragment.from(item)).isEmpty(),"Nonliving query did not return empty list");
        helper.assertTrue(Tricks.lookup(CMITricks.READ_POTION_EFFECTS.getPattern())==CMITricks.READ_POTION_EFFECTS,"Query pattern collides with actual registry");
        helper.assertTrue(GreatTricks.lookup(GreatPattern.TINCTURE_SHAPING)==GreatTricks.TINCTURE_SHAPING,"Great pattern lookup failed");
        item.discard();helper.succeed();
    }
    @GameTest(template="trickster_stock_test")
    public static void successfulShapingReturnsSameFragmentAndHiddenChangesCharge(GameTestHelper helper) {
        var player=player(helper);var ctx=context(player);var cow=helper.spawn(EntityType.COW,new BlockPos(1,1,1));
        var target=EntityFragment.from(cow);var potion=speed(100,3);
        helper.assertTrue(apply(ctx,target,potion)==target,"Shaping replaced its input EntityFragment");
        helper.assertTrue(BatteryAnima.available(player)==288000,"Shaping did not cost 32 Anima");
        failsFree(helper,player,()->apply(ctx,target,potion));
        // Vanilla returns false for this merge, even though it adds a hidden fallback.
        apply(ctx,target,speed(400,1));
        helper.assertTrue(BatteryAnima.available(player)==256000,"Hidden-only merge was not charged");
        var state=(CompoundTag)MobEffectInstance.CODEC.encodeStart(NbtOps.INSTANCE,cow.getEffect(MobEffects.MOVEMENT_SPEED)).getOrThrow();
        helper.assertTrue(state.getCompound("hidden_effect").getInt("duration")==400,"Vanilla hidden fallback merge did not run");
        var other=helper.spawn(EntityType.COW,new BlockPos(2,1,1));apply(ctx,EntityFragment.from(other),potion);
        helper.assertTrue(cow.getEffect(MobEffects.MOVEMENT_SPEED)!=other.getEffect(MobEffects.MOVEMENT_SPEED),"Targets share mutable effect instances");
        other.getEffect(MobEffects.MOVEMENT_SPEED).update(new MobEffectInstance(MobEffects.MOVEMENT_SPEED,500,5));
        helper.assertTrue(potion.toEffect().getDuration()==100&&cow.getEffect(MobEffects.MOVEMENT_SPEED).getAmplifier()==3,"Application mutated another target or snapshot");helper.succeed();
    }
    @GameTest(template="trickster_stock_test")
    public static void blockedEffectsRefundAcrossBatteriesWithoutRestoringOtherComponents(GameTestHelper helper) {
        var player=player(helper);var ctx=context(player);var cow=helper.spawn(EntityType.COW,new BlockPos(1,1,1));
        player.getInventory().setItem(0,battery(12000));player.getInventory().setItem(1,battery(20000));
        Consumer<MobEffectEvent.Applicable> listener=event->{
            if(event.getEntity()!=cow)return;
            player.getInventory().getItem(0).set(HexDataComponents.MEDIA,999L);
            event.setResult(MobEffectEvent.Applicable.Result.DO_NOT_APPLY);
        };
        NeoForge.EVENT_BUS.addListener(listener);
        try {
            failsFree(helper,player,()->apply(ctx,EntityFragment.from(cow),speed(100,0)));
            helper.assertTrue(BatteryAnima.get(player.getInventory().getItem(0))==12000&&BatteryAnima.get(player.getInventory().getItem(1))==20000,"Cross-battery refund changed distribution");
            helper.assertTrue(player.getInventory().getItem(0).get(HexDataComponents.MEDIA)==999L&&!cow.hasEffect(MobEffects.MOVEMENT_SPEED),"Refund overwrote unrelated components or applied blocked effect");
        }finally{NeoForge.EVENT_BUS.unregister(listener);}
        apply(ctx,EntityFragment.from(cow),speed(100,0));helper.assertTrue(BatteryAnima.available(player)==0,"Cross-battery commit failed");
        var zombie=helper.spawn(EntityType.ZOMBIE,new BlockPos(2,1,1));player.getInventory().setItem(0,battery(32000));
        failsFree(helper,player,()->apply(ctx,EntityFragment.from(zombie),PotionFragment.fromEffect(new MobEffectInstance(MobEffects.POISON,100))));
        helper.succeed();
    }
    @GameTest(template="trickster_stock_test")
    public static void invalidInputsAndInsufficientSoulNeverApplyEffects(GameTestHelper helper) {
        var player=player(helper);var ctx=context(player);var cow=helper.spawn(EntityType.COW,new BlockPos(1,1,1));
        for(var args:List.<List<Fragment>>of(List.of(),List.of(EntityFragment.from(cow)),List.of(EntityFragment.from(cow),new NumberFragment(1)),List.of(EntityFragment.from(cow),speed(100,1),speed(100,1))))
            failsFree(helper,player,()->GreatTricks.TINCTURE_SHAPING.activate(ctx,args));
        player.getInventory().setItem(0,battery(31999));
        failsFree(helper,player,()->apply(ctx,EntityFragment.from(cow),speed(100,1)));
        helper.assertTrue(!cow.hasEffect(MobEffects.MOVEMENT_SPEED),"Insufficient soul applied an effect");
        helper.succeed();
    }
    @GameTest(template="trickster_stock_test")
    public static void instantEffectIsAStoredStatusRatherThanAnImmediatePulse(GameTestHelper helper) {
        var player=player(helper);player.setHealth(5);var potion=PotionFragment.fromEffect(new MobEffectInstance(MobEffects.HEAL,3,0));
        helper.getLevel().addNewPlayer(player);
        apply(context(player),EntityFragment.from(player),potion);
        var instance=player.getEffect(MobEffects.HEAL);
        helper.assertTrue(instance!=null&&instance.getDuration()==3&&player.getHealth()==5,"Instant effect was changed to a one-shot pulse");
        instance.tick(player,()->{});instance.tick(player,()->{});
        helper.assertTrue(player.getHealth()==13&&potion.toEffect().getDuration()==3,"Instant stored status did not tick independently");
        player.discard();
        helper.succeed();
    }
    @GameTest(template="trickster_stock_test")
    public static void spatialRangeSentinelAndInversionUseNativeHexRules(GameTestHelper helper) {
        var player=player(helper);var ctx=context(player);var cow=helper.spawn(EntityType.COW,new BlockPos(1,1,1));var target=EntityFragment.from(cow);
        Object owner=new Object();var chain=CastChains.attach(new CastChain(),player,owner);var start=player.position();
        try(var ignored=CastChains.enter(chain)) {
            apply(ctx,target,speed(100,0));player.setPos(start.add(1000,0,0));
            failsFree(helper,player,()->apply(ctx,target,speed(200,1)));
            IXplatAbstractions.INSTANCE.setSentinel(player,new at.petrak.hexcasting.api.player.Sentinel(true,cow.position(),helper.getLevel().dimension()));
            apply(ctx,target,speed(200,1));IXplatAbstractions.INSTANCE.setSentinel(player,null);
            chain.toggle();apply(ctx,target,speed(300,2));
            player.setPos(start);failsFree(helper,player,()->apply(ctx,target,speed(400,3)));
            helper.succeed();
        }finally{IXplatAbstractions.INSTANCE.setSentinel(player,null);CastChains.release(chain,owner);}
    }
    @GameTest(template="trickster_stock_test")
    public static void playerTrueNamesRespectConfiguredDistanceExemption(GameTestHelper helper) throws Exception {
        var player=player(helper);
        // A native FakePlayer is a supported packet sink. A real ServerPlayer
        // borrowing its listener has no negotiated channel for effect sync.
        var recipient=FakePlayerFactory.get(helper.getLevel(),new GameProfile(UUID.randomUUID(),"PotionRecipient"));
        var connection=recipient.connection.getConnection();
        if(connection.channel()==null) {
            new io.netty.channel.embedded.EmbeddedChannel(connection);
            net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
        }
        recipient.setPos(player.position());recipient.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        helper.getLevel().addNewPlayer(recipient);
        var field=HexConfig.server().getClass().getDeclaredField("doesTrueNameHaveAmbit");field.setAccessible(true);
        var config=(net.neoforged.neoforge.common.ModConfigSpec.BooleanValue)field.get(null);boolean previous=config.get();
        Object owner=new Object();var chain=CastChains.attach(new CastChain(),player,owner);var start=player.position();var ctx=context(player);var target=EntityFragment.from(recipient);
        try(var ignored=CastChains.enter(chain)) {
            player.setPos(start.add(1000,0,0));config.set(false);
            failsFree(helper,player,()->apply(ctx,target,speed(100,0)));
            config.set(true);apply(ctx,target,speed(100,0));
            player.setPos(start);chain.toggle();apply(ctx,target,speed(200,1));
            config.set(false);failsFree(helper,player,()->apply(ctx,target,speed(300,2)));
            helper.succeed();
        }finally{config.set(previous);CastChains.release(chain,owner);recipient.discard();}
    }
    @GameTest(template="trickster_stock_test")
    public static void potionValuesSurviveTreesHexWrappingAndPausedExecutorCodecs(GameTestHelper helper) throws ReflectiveOperationException {
        var potion=speed(123,2);var delay=new SpellPart(new PatternGlyph(Tricks.DELAY_EXECUTION.getPattern()),List.of(new SpellPart(new NumberFragment(2))));
        var root=new SpellPart(new PatternGlyph(),List.of(new SpellPart(potion),delay));
        helper.assertTrue(Fragment.fromBytes(root.toBytes()).equals(root)&&root.deepClone().equals(root),"Tree codec or copy lost PotionFragment");
        var wrapped=FragmentConverter.fragmentToIota(potion);
        var tag=IotaType.TYPED_CODEC.encodeStart(NbtOps.INSTANCE,wrapped).getOrThrow();
        var decoded=(TrickIota)IotaType.TYPED_CODEC.parse(NbtOps.INSTANCE,tag).getOrThrow();
        helper.assertTrue(decoded.getSpell().glyph.equals(potion),"TrickIota wrapping lost PotionFragment");
        var player=player(helper);var source=new PlayerSpellSource(player);var executor=new DefaultSpellExecutor(root,List.of());SpellExecutor restored=null;
        try {
            helper.assertTrue(executor.run(source).isEmpty(),"Test executor did not pause");
            var serializer=EdmSerializer.of();SpellExecutor.INTERNAL_ENDEC.encode(SerializationContext.empty(),serializer,executor);
            restored=SpellExecutor.INTERNAL_ENDEC.decode(SerializationContext.empty(),EdmDeserializer.of(serializer.result()));
            Optional<Fragment> result=restored.run(source);int attempts=0;
            while(result.isEmpty()&&attempts++<5)result=restored.run(source);
            helper.assertTrue(result.isPresent(),"Saved executor did not finish");
            // NET_ENDEC carries the complete root tree, including the potion snapshot.
            var network=EdmSerializer.of();DefaultSpellExecutor.NET_ENDEC.encode(SerializationContext.empty(),network,executor);
            var netRestored=DefaultSpellExecutor.NET_ENDEC.decode(SerializationContext.empty(),EdmDeserializer.of(network.result()));
            var rootField=DefaultSpellExecutor.class.getDeclaredField("root");rootField.setAccessible(true);
            helper.assertTrue(rootField.get(netRestored).equals(root),"Executor network snapshot lost the potion value");
            helper.succeed();
        }finally {
            CastChains.release(((ExecutorChainAccess)(Object)executor).cmi$executorChain(),executor);
            if(restored!=null)CastChains.release(((ExecutorChainAccess)restored).cmi$executorChain(),restored);
        }
    }
}
