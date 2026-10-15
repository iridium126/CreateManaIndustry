package com.iridium126.createmanaindustry.compat.trickster;

import com.iridium126.createmanaindustry.compat.trickster.anima.BatteryAnima;
import com.iridium126.createmanaindustry.compat.trickster.casting.CastChain;
import com.iridium126.createmanaindustry.compat.trickster.casting.CastChains;
import com.iridium126.createmanaindustry.compat.trickster.casting.ExecutorChainAccess;
import com.iridium126.createmanaindustry.compat.trickster.fragment.GreatTrickGlyph;
import com.iridium126.createmanaindustry.compat.trickster.greattrick.GreatMode;
import com.iridium126.createmanaindustry.compat.trickster.greattrick.GreatPattern;
import com.iridium126.createmanaindustry.compat.trickster.greattrick.GreatTricks;
import com.iridium126.createmanaindustry.compat.trickster.mana.TricksterManaAccess;

import at.petrak.hexcasting.api.casting.iota.*;
import at.petrak.hexcasting.api.casting.mishaps.MishapOthersName;
import at.petrak.hexcasting.common.lib.HexDataComponents;
import com.iridium126.createmanaindustry.CMIComponents;
import com.iridium126.createmanaindustry.CMIFluids;
import com.mojang.authlib.GameProfile;
import dev.enjarai.trickster.spell.*;
import dev.enjarai.trickster.spell.execution.*;
import dev.enjarai.trickster.spell.execution.executor.DefaultSpellExecutor;
import dev.enjarai.trickster.spell.execution.source.PlayerSpellSource;
import dev.enjarai.trickster.spell.fragment.NumberFragment;
import dev.enjarai.trickster.spell.trick.Tricks;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class GreatTrickGameTests {
    private static ServerPlayer player(GameTestHelper helper) {
        return FakePlayerFactory.get(helper.getLevel(),new GameProfile(UUID.randomUUID(),"GreatTrickTest"));
    }
    private static ItemStack battery(long anima) {
        var stack=new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("hexcasting:battery")));
        stack.set(HexDataComponents.MEDIA_MAX,640000000L);stack.set(HexDataComponents.MEDIA,123456L);
        stack.set(CMIComponents.ANIMA.get(),anima);return stack;
    }
    private static SpellPart inversion() {return new SpellPart(new GreatTrickGlyph(GreatPattern.INVERSION));}
    @GameTest(template="trickster_stock_test")
    public static void greatTrickRegistryKeepsSavedIdsPatternsAndCosts(GameTestHelper helper) {
        var tricks=List.of(GreatTricks.INVERSION,GreatTricks.SHARED_FATE,GreatTricks.TINCTURE_SHAPING,GreatTricks.PARTITION);
        var ids=List.of("ectoplasmic_inversion","shared_fate","tincture_shaping","partition");
        var patterns=List.of(GreatPattern.INVERSION,GreatPattern.SHARED_FATE,GreatPattern.TINCTURE_SHAPING,GreatPattern.PARTITION);
        for(int i=0;i<tricks.size();i++) {
            var trick=tricks.get(i);
            helper.assertTrue(ResourceLocation.fromNamespaceAndPath("createmanaindustry",ids.get(i)).equals(GreatTricks.REGISTRY.getKey(trick)),"Saved great-trick ID changed");
            helper.assertTrue(GreatTricks.lookup(patterns.get(i))==trick,"Pattern registration changed");
            helper.assertTrue(!trick.getClass().isAnonymousClass(),"Great trick still uses an anonymous implementation");
            var glyph=new GreatTrickGlyph(patterns.get(i));
            helper.assertTrue(Fragment.fromBytes(glyph.toBytes()).equals(glyph),"Saved glyph changed after registration refactor");
        }
        helper.assertTrue(GreatTricks.INVERSION.estimateAnimaCost(List.of()).orElseThrow()==4000,"Inversion cost changed");
        helper.assertTrue(GreatTricks.SHARED_FATE.estimateAnimaCost(List.of()).orElseThrow()==32000,"Shared Fate cost changed");
        helper.assertTrue(GreatTricks.TINCTURE_SHAPING.estimateAnimaCost(List.of()).orElseThrow()==32000,"Shaping cost changed");
        helper.assertTrue(GreatTricks.PARTITION.estimateAnimaCost(List.of()).isEmpty(),"Partition lost dynamic pricing");
        helper.succeed();
    }
    @GameTest(template="trickster_stock_test")
    public static void inversionReturnsBooleanForItsNewState(GameTestHelper helper) {
        var player=player(helper);player.getInventory().setItem(0,battery(8000));
        Object owner=new Object();var chain=CastChains.attach(new CastChain(),player,owner);
        try(var ignored=CastChains.enter(chain)) {
            var source=new PlayerSpellSource(player);
            Fragment enabled=new DefaultSpellExecutor(inversion(),List.of()).run(source).orElseThrow();
            helper.assertTrue(enabled instanceof dev.enjarai.trickster.spell.fragment.BooleanFragment && enabled.asBoolean(),
                    "First inversion did not return BooleanFragment true");
            helper.assertTrue(chain.inverted()&&BatteryAnima.available(player)==4000,"Enabling state or Anima cost changed");
            Fragment disabled=new DefaultSpellExecutor(inversion(),List.of()).run(source).orElseThrow();
            helper.assertTrue(disabled instanceof dev.enjarai.trickster.spell.fragment.BooleanFragment && !disabled.asBoolean(),
                    "Second inversion did not return BooleanFragment false");
            helper.assertTrue(!chain.inverted()&&BatteryAnima.available(player)==0,"Disabling state or Anima cost changed");
            helper.succeed();
        } finally {CastChains.release(chain,owner);}
    }
    @GameTest(template="trickster_stock_test")
    public static void clearingStaffSessionRemovesItsNameExemption(GameTestHelper helper) {
        var player=player(helper);Object owner=new Object();var chain=CastChains.attach(new CastChain(),player,owner);
        chain.retain(chain);chain.toggle();CastChains.release(chain,owner);
        var image=new at.petrak.hexcasting.api.casting.eval.vm.CastingImage();
        image.getUserData().putString("cmi_cast_chain",chain.encode());
        at.petrak.hexcasting.xplat.IXplatAbstractions.INSTANCE.setStaffcastImage(player,image);
        helper.assertTrue(CastChains.nameExempt(helper.getLevel().getServer(),player.getUUID()),"Saving staff image cleared a live session");
        at.petrak.hexcasting.xplat.IXplatAbstractions.INSTANCE.clearCastingData(player);
        helper.assertTrue(!CastChains.nameExempt(helper.getLevel().getServer(),player.getUUID()),"Clearing staff session leaked exemption");
        helper.succeed();
    }
    @GameTest(template="trickster_stock_test")
    public static void animaPaymentCombinesBatteriesAndFailedToggleKeepsState(GameTestHelper helper) {
        var player=player(helper);player.getInventory().setItem(0,battery(2500));player.getInventory().setItem(1,battery(1500));
        Object owner=new Object();var chain=CastChains.attach(new CastChain(),player,owner);
        try(var ignored=CastChains.enter(chain)) {
            var ctx=new SpellContext(new ExecutionState(List.of()),new PlayerSpellSource(player),new TickData());
            GreatTricks.INVERSION.activate(ctx,List.of());
            helper.assertTrue(chain.inverted()&&BatteryAnima.available(player)==0,"Cross-battery 4 Anima payment failed");
            boolean failed=false;
            try {GreatTricks.INVERSION.activate(ctx,List.of());} catch(GreatTricks.AnimaBlunder expected) {failed=true;}
            helper.assertTrue(failed&&chain.inverted()&&BatteryAnima.available(player)==0,"Failed closing toggle changed state or resources");
            player.getInventory().setItem(0,battery(8000));
            var source=new PlayerSpellSource(player) {public Optional<ServerPlayer> getPlayer(){return Optional.empty();}};
            failed=false;
            try {new GreatTrickGlyph(GreatPattern.INVERSION).activateAsGlyph(new SpellContext(new ExecutionState(List.of()),source,new TickData()),List.of());}
            catch(dev.enjarai.trickster.spell.exception.blunder.NoPlayerBlunder expected) {failed=true;}
            helper.assertTrue(failed&&BatteryAnima.available(player)==8000,"Playerless great trick consumed soul");
            helper.succeed();
        } finally {CastChains.release(chain,owner);}
    }
    @GameTest(template="trickster_stock_test")
    public static void invertedFeesSimulateCommitAndRollback(GameTestHelper helper) {
        var player=player(helper);var battery=battery(8000);battery.set(HexDataComponents.MEDIA,400000L);
        player.getInventory().setItem(0,battery);
        var knot=new ItemStack(dev.enjarai.trickster.item.ModItems.AMETHYST_KNOT);
        var component=knot.get(dev.enjarai.trickster.item.component.ModComponents.MANA);
        knot.set(dev.enjarai.trickster.item.component.ModComponents.MANA,component.with(new dev.enjarai.trickster.spell.mana.SimpleManaPool(
                2000000,2000000,dev.enjarai.trickster.spell.mana.storage.ManaVariant.of(dev.enjarai.trickster.spell.mana.type.Manae.TRADITIONAL))));
        player.getInventory().setItem(1,knot);
        var chain=CastChains.attach(new CastChain(),player,new Object());chain.toggle();
        try(var ignored=CastChains.enter(chain)) {
            var env=new at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv(player,net.minecraft.world.InteractionHand.MAIN_HAND);
            helper.assertTrue(env.extractMedia(10000,true)==0,"Swapped mana precheck failed");
            helper.assertTrue(TricksterManaAccess.getMana(knot,helper.getLevel())==2000,"Mana simulation changed knot");
            helper.assertTrue(env.extractMedia(10000,false)==0,"Swapped mana payment failed");
            helper.assertTrue(Math.abs(TricksterManaAccess.getMana(knot,helper.getLevel())-1897.6)<0.01,"Media cost did not become doubled mana");
            helper.assertTrue(battery.get(HexDataComponents.MEDIA)==400000L,"Hex operation still consumed media");
            var state=new ExecutionState(List.of());var ctx=new SpellContext(state,new PlayerSpellSource(player),new TickData());
            ctx.checkScaledMana(1024);
            try(var transaction=net.fabricmc.fabric.api.transfer.v1.transaction.Transaction.openOuter()) {
                ctx.useScaledMana(1024,transaction);
                helper.assertTrue(battery.get(HexDataComponents.MEDIA)==0,"Mana fee did not use doubled media");
            }
            helper.assertTrue(battery.get(HexDataComponents.MEDIA)==400000L,"Aborted media payment was not restored");
            try(var transaction=net.fabricmc.fabric.api.transfer.v1.transaction.Transaction.openOuter()) {
                ctx.useScaledMana(1024,transaction);transaction.commit();
            }
            helper.assertTrue(battery.get(HexDataComponents.MEDIA)==0,"Committed media payment was not retained");
            helper.succeed();
        } finally {chain.suspend();CastChains.release(chain,chain);}
    }
    @GameTest(template="trickster_stock_test")
    public static void simultaneousChainsAndHexRangeStayIndependent(GameTestHelper helper) {
        var player=player(helper);player.setPos(helper.absolutePos(net.minecraft.core.BlockPos.ZERO).getX(),0,0);
        Object firstOwner=new Object(),secondOwner=new Object();
        var first=CastChains.attach(new CastChain(),player,firstOwner);first.toggle();
        var second=CastChains.attach(new CastChain(),player,secondOwner);second.toggle();
        var env=new at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv(player,net.minecraft.world.InteractionHand.MAIN_HAND);
        var near=player.position();var far=near.add(100,0,0);
        try {
            try(var ignored=CastChains.enter(first)) {
                helper.assertTrue(!env.isVecInRange(near)&&env.isVecInRange(far),"Hexcasting spatial range was not inverted");
                var other=player(helper);
                var otherEnv=new at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv(other,net.minecraft.world.InteractionHand.MAIN_HAND);
                helper.assertTrue(otherEnv.isVecInRange(other.position()),"Global name exemption leaked into another caster's reach");
                first.toggle();helper.assertTrue(env.isVecInRange(near)&&!env.isVecInRange(far),"Second toggle did not restore range");
            }
            helper.assertTrue(CastChains.nameExempt(helper.getLevel().getServer(),player.getUUID()),"Disabling one chain removed another chain's exemption");
            CastChains.release(second,secondOwner);
            helper.assertTrue(!CastChains.nameExempt(helper.getLevel().getServer(),player.getUUID()),"Last inverted chain ended without removing exemption");
            helper.succeed();
        } finally {CastChains.release(first,firstOwner);CastChains.release(second,secondOwner);}
    }
    @GameTest(template="trickster_stock_test")
    public static void mirrorModeRoundTripsInOpeningData(GameTestHelper helper) {
        var data=new dev.enjarai.trickster.screen.ScrollAndQuillScreenHandler.InitialData(new SpellPart(),true,true,
                net.minecraft.world.InteractionHand.MAIN_HAND,42,Set.of());
        ((GreatMode)(Object)data).cmi$greatMode(true);
        var serializer=io.wispforest.endec.format.edm.EdmSerializer.of();
        dev.enjarai.trickster.screen.ScrollAndQuillScreenHandler.InitialData.ENDEC.encode(io.wispforest.endec.SerializationContext.empty(),serializer,data);
        var restored=dev.enjarai.trickster.screen.ScrollAndQuillScreenHandler.InitialData.ENDEC.decode(io.wispforest.endec.SerializationContext.empty(),
                io.wispforest.endec.format.edm.EdmDeserializer.of(serializer.result()));
        helper.assertTrue(((GreatMode)(Object)restored).cmi$greatMode(),"Server-confirmed great mode was not synchronized");
        helper.succeed();
    }
    @GameTest(template="trickster_stock_test")
    public static void animaFillingPreservesMediaAndStopsAtCapacity(GameTestHelper helper) {
        var original=battery(0);net.minecraft.world.level.material.Fluid soul=CMIFluids.LIQUID_SOUL.getSource();
        var fluid=new FluidStack(soul,1000);
        var filled=BatteryAnima.fill(original,1000,fluid);
        helper.assertTrue(BatteryAnima.get(filled)==32000,"One bucket did not give 32 Anima");
        helper.assertTrue(BatteryAnima.get(original)==0,"Simulation modified original battery");
        helper.assertTrue(filled.get(HexDataComponents.MEDIA)==123456L,"Soul filling overwrote media");
        long max=BatteryAnima.capacity(filled);filled.set(CMIComponents.ANIMA.get(),max-64);
        helper.assertTrue(BatteryAnima.required(filled,fluid)==2,"Partial fill exceeded capacity");
        filled=BatteryAnima.fill(filled,2,fluid);
        helper.assertTrue(BatteryAnima.required(filled,fluid)==-1,"Full battery still accepts soul");helper.succeed();
    }
    @GameTest(template="trickster_stock_test")
    public static void longSpellKeepsInversionAndGlobalNameExemption(GameTestHelper helper) {
        var player=player(helper);player.getInventory().setItem(0,battery(12000));
        var delay=new SpellPart(new PatternGlyph(Tricks.DELAY_EXECUTION.getPattern()),List.of(new SpellPart(new NumberFragment(2))));
        var root=new SpellPart(new PatternGlyph(),List.of(inversion(),delay,inversion()));
        var executor=new DefaultSpellExecutor(root,List.of());var source=new PlayerSpellSource(player);
        try {
            helper.assertTrue(executor.run(source).isEmpty(),"Delayed spell finished immediately");
            helper.assertTrue(BatteryAnima.available(player)==8000,"Opening inversion did not cost 4 Anima");
            helper.assertTrue(CastChains.nameExempt(helper.getLevel().getServer(),player.getUUID()),"Long spell lost global exemption");
            // UUID-based exemption works even when this entity cannot be resolved in this level.
            var self=new EntityIota(player.getUUID(),null,true);
            helper.assertTrue(MishapOthersName.getTrueNameMishapFromDatum(helper.getLevel(),self,null)==null,"Playerless scan did not skip protected name");
            var foreign=new EntityIota(UUID.randomUUID(),null,true);
            helper.assertTrue(MishapOthersName.getTrueNameMishapFromDatum(helper.getLevel(),new ListIota(List.of(self,foreign)),null)!=null,
                    "Protected name hid another forbidden name");
            Optional<Fragment> result=Optional.empty();for(int i=0;i<5&&result.isEmpty();i++)result=executor.run(source);
            helper.assertTrue(result.isPresent(),"Long spell did not complete");
            helper.assertTrue(BatteryAnima.available(player)==4000,"Closing inversion did not cost 4 Anima");
            helper.assertTrue(!CastChains.nameExempt(helper.getLevel().getServer(),player.getUUID()),"Finished chain retained name exemption");
            helper.succeed();
        } finally {CastChains.release(((ExecutorChainAccess)(Object)executor).cmi$executorChain(),executor);}
    }
    @GameTest(template="trickster_stock_test")
    public static void mixedTreeAndRunningChainRoundTrip(GameTestHelper helper) {
        var player=player(helper);player.getInventory().setItem(0,battery(8000));
        var delay=new SpellPart(new PatternGlyph(Tricks.DELAY_EXECUTION.getPattern()),List.of(new SpellPart(new NumberFragment(2))));
        var root=new SpellPart(new PatternGlyph(),List.of(inversion(),delay));
        helper.assertTrue(Fragment.fromBytes(root.toBytes()).equals(root),"Great glyph was lost in compact spell serialization");
        var executor=new DefaultSpellExecutor(root,List.of());var source=new PlayerSpellSource(player);
        try {
            executor.run(source);
            // The executor codec has a distinct type; native executor state layouts remain unchanged.
            var codec=SpellExecutor.INTERNAL_ENDEC;
            var serializer=io.wispforest.endec.format.edm.EdmSerializer.of();
            codec.encode(io.wispforest.endec.SerializationContext.empty(),serializer,executor);
            var restored=codec.decode(io.wispforest.endec.SerializationContext.empty(),
                    io.wispforest.endec.format.edm.EdmDeserializer.of(serializer.result()));
            helper.assertTrue(((ExecutorChainAccess)restored).cmi$executorChain().inverted(),"Saved executor lost inversion");
            helper.assertTrue(!((ExecutorChainAccess)restored).cmi$executorChain().live(),"Deserialization registered a suspended chain");
            helper.succeed();
        } finally {CastChains.release(((ExecutorChainAccess)(Object)executor).cmi$executorChain(),executor);}
    }
}
