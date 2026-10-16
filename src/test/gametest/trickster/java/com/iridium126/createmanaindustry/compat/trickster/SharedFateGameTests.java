package com.iridium126.createmanaindustry.compat.trickster;

import com.iridium126.createmanaindustry.compat.trickster.anima.BatteryAnima;
import com.iridium126.createmanaindustry.compat.trickster.blunder.SharedFateBlunder;
import com.iridium126.createmanaindustry.compat.trickster.casting.CastChain;
import com.iridium126.createmanaindustry.compat.trickster.casting.CastChains;
import com.iridium126.createmanaindustry.compat.trickster.casting.ExecutorChainAccess;
import com.iridium126.createmanaindustry.compat.trickster.fragment.GreatTrickGlyph;
import com.iridium126.createmanaindustry.compat.trickster.greattrick.GreatPattern;
import com.iridium126.createmanaindustry.compat.trickster.greattrick.GreatTricks;
import com.iridium126.createmanaindustry.compat.trickster.sharedfate.SharedFateManager;
import com.iridium126.createmanaindustry.compat.trickster.sharedfate.SharedFateMishaps;

import at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv;
import at.petrak.hexcasting.api.casting.eval.vm.*;
import at.petrak.hexcasting.api.casting.mishaps.*;
import at.petrak.hexcasting.api.casting.eval.sideeffects.OperatorSideEffect;
import at.petrak.hexcasting.common.lib.HexDataComponents;
import com.iridium126.createmanaindustry.CMIComponents;
import com.mojang.authlib.GameProfile;
import dev.enjarai.trickster.spell.*;
import dev.enjarai.trickster.spell.execution.*;
import dev.enjarai.trickster.spell.execution.source.PlayerSpellSource;
import dev.enjarai.trickster.spell.fragment.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class SharedFateGameTests {
    private static ServerPlayer player(GameTestHelper helper,boolean add) {
        var sink=FakePlayerFactory.get(helper.getLevel(),new GameProfile(UUID.randomUUID(),"SharedFateTest"));
        var player=new ServerPlayer(helper.getLevel().getServer(),helper.getLevel(),sink.getGameProfile(),
                net.minecraft.server.level.ClientInformation.createDefault());
        player.connection=sink.connection;
        var position=helper.absolutePos(BlockPos.ZERO).getCenter();player.setPos(position.x,position.y,position.z);
        player.setHealth(20);
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        player.getAbilities().invulnerable=false;
        if(add)helper.getLevel().addNewPlayer(player);
        var battery=new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("hexcasting:battery")));
        battery.set(HexDataComponents.MEDIA_MAX,640000000L);battery.set(CMIComponents.ANIMA.get(),320000L);
        player.getInventory().setItem(0,battery);return player;
    }
    private static CastChain chain(ServerPlayer player,Object owner){return CastChains.attach(new CastChain(),player,owner);}
    private static SpellPart binding(Entity target) {
        return new SpellPart(new GreatTrickGlyph(GreatPattern.SHARED_FATE),List.of(new SpellPart(EntityFragment.from(target))));
    }
    private static SpellPart delayedBinding(Entity target) {
        return delayed(binding(target));
    }
    private static SpellPart delayed(SpellPart binding) {
        var delay=new SpellPart(new PatternGlyph(dev.enjarai.trickster.spell.trick.Tricks.DELAY_EXECUTION.getPattern()),
                List.of(new SpellPart(new NumberFragment(20))));
        return new SpellPart(new PatternGlyph(),List.of(binding,delay));
    }
    @GameTest(template="trickster_stock_test")
    public static void hexEntityArgumentBindsAndReturnsNativeEntityIota(GameTestHelper helper) {
        var caster=player(helper,false);var target=helper.spawn(EntityType.COW,new BlockPos(1,1,1));
        var bind=new SpellPart(new GreatTrickGlyph(GreatPattern.SHARED_FATE),List.of(new SpellPart(
                new PatternGlyph(dev.enjarai.trickster.spell.trick.Tricks.LOAD_ARGUMENT_1.getPattern()))));
        var image=new CastingImage();
        image=image.copy(at.petrak.hexcasting.api.utils.TreeList.from(List.of(new at.petrak.hexcasting.api.casting.iota.ListIota(
                        List.of(new at.petrak.hexcasting.api.casting.iota.EntityIota(target))))),
                0,at.petrak.hexcasting.api.utils.TreeList.empty(),false,false,0,image.getUserData());
        var vm=new CastingVM(image,new StaffCastEnv(caster,InteractionHand.MAIN_HAND));long balance=BatteryAnima.available(caster);
        try {
            vm.queueExecuteAndWrapIota(new com.iridium126.createmanaindustry.compat.hexcasting.TrickIota(bind),helper.getLevel());
            helper.assertTrue(BatteryAnima.available(caster)==balance-32000,"Valid Hex entity argument did not reach Shared Fate's Entity signature");
            helper.assertTrue(vm.getImage().getStack().last() instanceof at.petrak.hexcasting.api.casting.iota.EntityIota entity
                    && entity.getEntityId().equals(target.getUUID())&&!entity.isPlayer(),"Shared Fate returned a wrapped spell instead of the target EntityIota");
            helper.assertTrue(SharedFateManager.outgoing(caster.getServer(),caster.getUUID()).equals(List.of(target.getUUID())),"Hex entity argument created no binding");
            incident(caster,halfHealth());
            helper.assertTrue(target.getHealth()==target.getMaxHealth()/2,"Hex argument binding did not transmit mishap");
            helper.succeed();
        } finally {CastChains.resetStaff(caster,vm.getImage().getUserData().getString("cmi_cast_chain"));}
    }
    @GameTest(template="trickster_stock_test")
    public static void mirrorCanBindEntityReadFromHexFocus(GameTestHelper helper) {
        var caster=player(helper,false);var target=helper.spawn(EntityType.COW,new BlockPos(1,1,1));
        // Load a saved focus: direct component setters reflect on Hex's client-only Font signature.
        var saved=new net.minecraft.nbt.CompoundTag();saved.putString("id","hexcasting:focus");saved.putInt("count",1);
        var components=new net.minecraft.nbt.CompoundTag();
        components.put("hexcasting:iota",at.petrak.hexcasting.api.casting.iota.IotaType.TYPED_CODEC.encodeStart(
                net.minecraft.nbt.NbtOps.INSTANCE,new at.petrak.hexcasting.api.casting.iota.EntityIota(target)).getOrThrow());
        saved.put("components",components);
        var focus=ItemStack.parseOptional(helper.getLevel().registryAccess(),saved);
        caster.setItemInHand(InteractionHand.OFF_HAND,focus);
        var bind=new SpellPart(new GreatTrickGlyph(GreatPattern.SHARED_FATE),List.of(new SpellPart(
                new PatternGlyph(CMITricks.Hexcasting.READ_IOTA.getPattern()))));
        var manager=new WrappedSpellExecutionManager(new PlayerSpellExecutionManager(5));
        var source=new PlayerSpellSource(caster,manager);long balance=BatteryAnima.available(caster);
        var context=new SpellContext(new ExecutionState(List.of()),source,new TickData());
        var read=CMITricks.Hexcasting.READ_IOTA.run(context,Optional.empty()).orElseThrow();
        helper.assertTrue(read instanceof EntityFragment entity&&entity.uuid().equals(target.getUUID()),"Focus reader did not produce native EntityFragment");
        manager.setSpell(delayed(bind));
        manager.tick(source,(slot,executor)->{},(slot,executor,result)->{},(slot,executor)->{});
        helper.assertTrue(BatteryAnima.available(caster)==balance-32000,"Reading entity iota in the mirror did not produce EntityFragment");
        helper.runAfterDelay(2,()->{
            try {
                helper.assertTrue(SharedFateManager.outgoing(caster.getServer(),caster.getUUID()).equals(List.of(target.getUUID())),"Mirror created no active binding from its read entity");
                incident(caster,halfHealth());helper.assertTrue(target.getHealth()==target.getMaxHealth()/2,"Mirror read binding did not transmit mishap");
                helper.succeed();
            } finally {manager.killAll();}
        });
    }
    @GameTest(template="trickster_stock_test")
    public static void mirrorQueuedSpellKeepsBindingAcrossTicksAndReplaysMishap(GameTestHelper helper) {
        var caster=player(helper,false);var target=helper.spawn(EntityType.COW,new BlockPos(1,1,1));
        var manager=new PlayerSpellExecutionManager(5);var source=new PlayerSpellSource(caster,manager);
        long balance=BatteryAnima.available(caster);
        manager.queueAndCast(source,delayedBinding(target),List.of(),Optional.empty());
        helper.assertTrue(BatteryAnima.available(caster)==balance-32000,"Mirror did not charge the successful binding");
        helper.runAfterDelay(2,()->{
            try {
                helper.assertTrue(SharedFateManager.outgoing(caster.getServer(),caster.getUUID()).equals(List.of(target.getUUID())),
                        "Mirror binding disappeared before its long spell finished");
                incident(caster,halfHealth());
                helper.assertTrue(target.getHealth()==target.getMaxHealth()/2,"Mirror binding did not replay the actual mishap");
                manager.killAll();
                helper.assertTrue(SharedFateManager.outgoing(caster.getServer(),caster.getUUID()).isEmpty(),"Cancelled mirror spell retained binding");
                helper.succeed();
            } finally {manager.killAll();}
        });
    }
    @GameTest(template="trickster_stock_test")
    public static void staffTrickIotaKeepsBindingForNextVmAndReplaysRealDivisionMishap(GameTestHelper helper) {
        var caster=player(helper,false);var target=helper.spawn(EntityType.COW,new BlockPos(1,1,1));
        var vm=new CastingVM(new CastingImage(),new StaffCastEnv(caster,InteractionHand.MAIN_HAND));
        var result=vm.queueExecuteAndWrapIota(new com.iridium126.createmanaindustry.compat.hexcasting.TrickIota(binding(target)),helper.getLevel());
        helper.assertTrue(result.getResolutionType().getSuccess()&&!result.isStackClear(),"Valid staff TrickIota failed");
        var xplat=at.petrak.hexcasting.xplat.IXplatAbstractions.INSTANCE;
        xplat.setStaffcastImage(caster,vm.getImage());
        helper.runAfterDelay(2,()->{
            try {
                helper.assertTrue(SharedFateManager.outgoing(caster.getServer(),caster.getUUID()).equals(List.of(target.getUUID())),
                        "Staff binding disappeared between drawn patterns");
                var resumed=xplat.getStaffcastVM(caster,InteractionHand.MAIN_HAND);
                var image=resumed.getImage();
                resumed.setImage(image.copy(at.petrak.hexcasting.api.utils.TreeList.from(List.of(
                        new at.petrak.hexcasting.api.casting.iota.DoubleIota(1),new at.petrak.hexcasting.api.casting.iota.DoubleIota(0))),
                        0,at.petrak.hexcasting.api.utils.TreeList.empty(),false,false,0,image.getUserData()));
                resumed.queueExecuteAndWrapIota(new at.petrak.hexcasting.api.casting.iota.PatternIota(
                        at.petrak.hexcasting.common.lib.hex.HexActions.DIV_CROSS.value().prototype()),helper.getLevel());
                helper.assertTrue(target.getHealth()==target.getMaxHealth()/2,"Staff binding did not replay the VM's division mishap");
                helper.succeed();
            } finally {xplat.setStaffcastImage(caster,null);}
        });
    }
    @GameTest(template="trickster_stock_test")
    public static void handedOffTrickIotaKeepsBindingWhenStaffStackClears(GameTestHelper helper) {
        var caster=player(helper,false);var target=helper.spawn(EntityType.COW,new BlockPos(1,1,1));
        var source=new PlayerSpellSource(caster);var manager=source.getExecutionManager().orElseThrow();manager.killAll();
        var vm=new CastingVM(new CastingImage(),new StaffCastEnv(caster,InteractionHand.MAIN_HAND));
        var result=vm.queueExecuteAndWrapIota(new com.iridium126.createmanaindustry.compat.hexcasting.TrickIota(delayedBinding(target)),helper.getLevel());
        helper.assertTrue(result.getResolutionType().getSuccess()&&result.isStackClear(),"Long TrickIota did not hand off");
        helper.runAfterDelay(2,()->{
            try {
                helper.assertTrue(SharedFateManager.outgoing(caster.getServer(),caster.getUUID()).equals(List.of(target.getUUID())),
                        "Clearing staff stack prematurely ended the handed-off spell's binding");
                incident(caster,halfHealth());
                helper.assertTrue(target.getHealth()==target.getMaxHealth()/2,"Handed-off binding did not replay mishap");
                helper.succeed();
            } finally {manager.killAll();}
        });
    }
    @GameTest(template="trickster_stock_test")
    public static void fullMishapPrimitivesUseRecipientAttributesAndOriginalContext(GameTestHelper helper) {
        var a=player(helper,false);var b=player(helper,true);var cow=helper.spawn(EntityType.COW,new BlockPos(1,1,1));
        var item=new net.minecraft.world.entity.item.ItemEntity(helper.getLevel(),a.getX(),a.getY(),a.getZ(),new ItemStack(net.minecraft.world.item.Items.STONE));helper.getLevel().addFreshEntity(item);
        b.giveExperiencePoints(100);b.setItemInHand(InteractionHand.OFF_HAND,new ItemStack(net.minecraft.world.item.Items.DIAMOND));
        cow.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(net.minecraft.world.item.Items.EMERALD));
        Object owner=new Object();var chain=chain(a,owner);var context=new Mishap.Context(null,null);var calls=new java.util.concurrent.atomic.AtomicInteger();
        try {
            SharedFateManager.bind(a,b,chain,32000);SharedFateManager.bind(a,cow,chain,32000);SharedFateManager.bind(a,item,chain,32000);
            Mishap mishap=new Mishap(){
                public at.petrak.hexcasting.api.pigment.FrozenPigment accentColor(at.petrak.hexcasting.api.casting.eval.CastingEnvironment env,Mishap.Context ctx){return at.petrak.hexcasting.api.pigment.FrozenPigment.DEFAULT.get();}
                protected net.minecraft.network.chat.Component errorMessage(at.petrak.hexcasting.api.casting.eval.CastingEnvironment env,Mishap.Context ctx){return null;}
                public at.petrak.hexcasting.api.utils.TreeList<at.petrak.hexcasting.api.casting.iota.Iota> execute(at.petrak.hexcasting.api.casting.eval.CastingEnvironment env,Mishap.Context ctx,at.petrak.hexcasting.api.utils.TreeList<at.petrak.hexcasting.api.casting.iota.Iota> stack){
                    helper.assertTrue(ctx==context,"Replay replaced the original error context");calls.incrementAndGet();
                    var effect=env.getMishapEnvironment();effect.drown();effect.blind(80);effect.nauseate(60);effect.removeXp(17);effect.dropHeldItems();
                    return stack;
                }
            };
            new OperatorSideEffect.DoMishap(mishap,context).performEffect(new CastingVM(new CastingImage(),new StaffCastEnv(a,InteractionHand.MAIN_HAND)));
            helper.assertTrue(calls.get()==4,"A complete Mishap was not executed on every Entity recipient");
            helper.assertTrue(b.getAirSupply()==0&&cow.getAirSupply()==0,"Recipient air attributes ignored");
            helper.assertTrue(b.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS)&&cow.hasEffect(net.minecraft.world.effect.MobEffects.CONFUSION),"Recipient states not applied");
            helper.assertTrue(b.totalExperience==83&&b.getOffhandItem().isEmpty()&&cow.getMainHandItem().isEmpty(),"Recipient experience or hand items ignored");
            helper.assertTrue(item.isAlive()&&!item.getItem().isEmpty(),"Unsupported item Entity attributes changed");helper.succeed();
        }finally{CastChains.release(chain,owner);b.discard();item.discard();}
    }
    @GameTest(template="trickster_stock_test")
    public static void replaySpendsOwnMediaAndKeepsTrueNameExemption(GameTestHelper helper) {
        var a=player(helper,false);var b=player(helper,true);Object owner=new Object();
        var bindings=chain(a,owner);var upstream=chain(a,owner);var protectedChain=chain(b,owner);
        a.getInventory().getItem(0).set(HexDataComponents.MEDIA,100000L);
        b.getInventory().getItem(0).set(HexDataComponents.MEDIA,100000L);
        try {
            SharedFateManager.bind(a,b,bindings,32000);upstream.toggle();
            try(var ignored=CastChains.enter(upstream)){incident(a,new MishapNotEnoughMedia(1000));}
            helper.assertTrue(b.getInventory().getItem(0).get(HexDataComponents.MEDIA)==99000,"Replay inherited upstream inversion instead of spending target's media");
            protectedChain.toggle();incident(a,new MishapOthersName(b));
            helper.assertTrue(a.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS)&&!b.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS),"Replay ignored recipient's own true-name exemption");helper.succeed();
        }finally{CastChains.release(bindings,owner);CastChains.release(upstream,owner);CastChains.release(protectedChain,owner);b.discard();}
    }
    @GameTest(template="trickster_stock_test")
    public static void longExecutorCodecPreservesTargetsAndResumesWithoutCharging(GameTestHelper helper) {
        var player=player(helper,false);var cow=helper.spawn(EntityType.COW,new BlockPos(1,1,1));
        var bind=new SpellPart(new GreatTrickGlyph(GreatPattern.SHARED_FATE),List.of(new SpellPart(EntityFragment.from(cow))));
        var delay=new SpellPart(new PatternGlyph(dev.enjarai.trickster.spell.trick.Tricks.DELAY_EXECUTION.getPattern()),List.of(new SpellPart(new NumberFragment(2))));
        var root=new SpellPart(new PatternGlyph(),List.of(bind,delay));
        var executor=new dev.enjarai.trickster.spell.execution.executor.DefaultSpellExecutor(root,List.of());
        var source=new PlayerSpellSource(player);
        CastChain original=((ExecutorChainAccess)(Object)executor).cmi$executorChain();
        SpellExecutor restored=null;
        try {
            helper.assertTrue(executor.run(source).isEmpty(),"Long spell did not pause");
            original=((ExecutorChainAccess)(Object)executor).cmi$executorChain();
            long balance=BatteryAnima.available(player);
            var serializer=io.wispforest.endec.format.edm.EdmSerializer.of();
            var codec=SpellExecutor.INTERNAL_ENDEC;
            codec.encode(io.wispforest.endec.SerializationContext.empty(),serializer,executor);
            restored=codec.decode(io.wispforest.endec.SerializationContext.empty(),io.wispforest.endec.format.edm.EdmDeserializer.of(serializer.result()));
            var saved=((ExecutorChainAccess)restored).cmi$executorChain();
            helper.assertTrue(saved.fateTargets().equals(List.of(cow.getUUID()))&&!saved.live(),"Executor codec lost targets or registered suspended edges");
            SharedFateManager.suspend(helper.getLevel().getServer(),player.getUUID());original.suspend();
            CastChains.release(original,executor);
            helper.assertTrue(restored.run(source).isEmpty(),"Resumed delay did not retain a running chain");
            helper.assertTrue(((ExecutorChainAccess)restored).cmi$executorChain().fateTargets().contains(cow.getUUID()),"Resumed targets lost");
            helper.assertTrue(BatteryAnima.available(player)==balance,"Resuming charged Anima");
            Optional<Fragment> result=restored.run(source);int attempts=0;
            while(result.isEmpty()&&attempts++<5)result=restored.run(source);
            helper.assertTrue(result.isPresent(),"Restored long spell never finished");
            helper.assertTrue(!((ExecutorChainAccess)restored).cmi$executorChain().live(),"Finished continuation retained binding owner");
            helper.succeed();
        }finally{
            CastChains.release(original,executor);
            if(restored!=null)CastChains.release(((ExecutorChainAccess)restored).cmi$executorChain(),restored);
        }
    }
    @GameTest(template="trickster_stock_test")
    public static void sentinelExtendsSpatialAmbitAndRemovalBreaksBinding(GameTestHelper helper) {
        var player=player(helper,false);var cow=helper.spawn(EntityType.COW,new BlockPos(1,1,1));
        Object owner=new Object();var chain=chain(player,owner);
        // Keep the target in an entity-ticking fixture chunk; move the source beyond its ordinary radius.
        player.setPos(player.getX()+100,player.getY(),player.getZ());
        at.petrak.hexcasting.xplat.IXplatAbstractions.INSTANCE.setSentinel(player,new at.petrak.hexcasting.api.player.Sentinel(true,cow.position(),helper.getLevel().dimension()));
        try {
            SharedFateManager.bind(player,cow,chain,32000);
            helper.assertTrue(chain.fateTargets().contains(cow.getUUID()),"Sentinel spatial range was ignored");
            at.petrak.hexcasting.xplat.IXplatAbstractions.INSTANCE.setSentinel(player,null);
            SharedFateManager.refresh(helper.getLevel().getServer());
            helper.assertTrue(chain.fateTargets().isEmpty(),"Removing sentinel did not detach now-out-of-range target");helper.succeed();
        }finally{CastChains.release(chain,owner);}
    }
    @GameTest(template="trickster_stock_test")
    public static void invalidInputsAndInsufficientSoulLeaveEveryChainUnchanged(GameTestHelper helper) {
        var player=player(helper,false);var cow=helper.spawn(EntityType.COW,new BlockPos(1,1,1));
        Object owner=new Object();var first=chain(player,owner);var second=chain(player,owner);
        try(var ignored=CastChains.enter(first)) {
            var ctx=new SpellContext(new ExecutionState(List.of()),new PlayerSpellSource(player),new TickData());
            for(var args:List.<List<Fragment>>of(List.of(),List.of(new NumberFragment(1)),List.of(EntityFragment.from(cow),EntityFragment.from(cow)))) {
                boolean failed=false;long balance=BatteryAnima.available(player);
                try{GreatTricks.SHARED_FATE.activate(ctx,args);}catch(dev.enjarai.trickster.spell.exception.blunder.InvalidInputsBlunder expected){failed=true;}
                helper.assertTrue(failed&&BatteryAnima.available(player)==balance,"Invalid signature charged Anima");
            }
            SharedFateManager.bind(player,cow,first,32000);long balance=BatteryAnima.available(player);boolean failed=false;
            try{SharedFateManager.bind(player,cow,second,32000);}catch(SharedFateBlunder expected){failed=true;}
            helper.assertTrue(failed&&BatteryAnima.available(player)==balance,"Cross-chain duplicate accepted");
            CastChains.release(first,owner);
            player.getInventory().getItem(0).set(CMIComponents.ANIMA.get(),31000L);failed=false;
            try{SharedFateManager.bind(player,cow,second,32000);}catch(GreatTricks.AnimaBlunder expected){failed=true;}
            helper.assertTrue(failed&&BatteryAnima.available(player)==31000&&second.fateTargets().isEmpty(),"Insufficient soul changed bindings or balance");
            cow.setPos(player.getX()+100,player.getY(),player.getZ());failed=false;
            try{SharedFateManager.bind(player,cow,second,32000);}catch(SharedFateBlunder expected){failed=true;}
            helper.assertTrue(failed&&BatteryAnima.available(player)==31000,"Out-of-range target charged soul");
            helper.succeed();
        }finally{CastChains.release(first,owner);CastChains.release(second,owner);}
    }
    private static Mishap halfHealth(){return new MishapDivideByZero(net.minecraft.network.chat.Component.literal("1"),net.minecraft.network.chat.Component.literal("0"),"divide");}
    private static void incident(ServerPlayer caster,Mishap mishap){
        new OperatorSideEffect.DoMishap(mishap,new Mishap.Context(null,null)).performEffect(new CastingVM(new CastingImage(),new StaffCastEnv(caster,InteractionHand.MAIN_HAND)));
    }
    @GameTest(template="trickster_stock_test")
    public static void finalFatalIncidentStillCascadesAndNonlivingTargetsHaveNoInventedHealth(GameTestHelper helper) {
        var a=player(helper,true);var b=player(helper,true);var cow=helper.spawn(EntityType.COW,new BlockPos(1,1,1));
        var item=new net.minecraft.world.entity.item.ItemEntity(helper.getLevel(),a.getX(),a.getY(),a.getZ(),new ItemStack(net.minecraft.world.item.Items.STONE));helper.getLevel().addFreshEntity(item);
        Object owner=new Object();var ac=chain(a,owner);var bc=chain(b,owner);
        try {
            SharedFateManager.bind(a,b,ac,32000);SharedFateManager.bind(b,cow,bc,32000);SharedFateManager.bind(a,item,ac,32000);
            // Stock primitive operations still execute through the same captured Mishap instance.
            Mishap lethal=new Mishap(){
                @Override public at.petrak.hexcasting.api.pigment.FrozenPigment accentColor(at.petrak.hexcasting.api.casting.eval.CastingEnvironment env,Mishap.Context context){return at.petrak.hexcasting.api.pigment.FrozenPigment.DEFAULT.get();}
                @Override protected net.minecraft.network.chat.Component errorMessage(at.petrak.hexcasting.api.casting.eval.CastingEnvironment env,Mishap.Context context){return null;}
                @Override public at.petrak.hexcasting.api.utils.TreeList<at.petrak.hexcasting.api.casting.iota.Iota> execute(at.petrak.hexcasting.api.casting.eval.CastingEnvironment env,Mishap.Context context,at.petrak.hexcasting.api.utils.TreeList<at.petrak.hexcasting.api.casting.iota.Iota> stack){
                    env.getMishapEnvironment().damage(1);return stack;
                }
            };
            incident(a,lethal);
            helper.assertTrue(!a.isAlive()&&!b.isAlive()&&!cow.isAlive(),"Fatal incident failed to pass through a dying intermediate player");
            helper.assertTrue(item.isAlive()&&!item.getItem().isEmpty(),"Nonliving target acquired fictitious health");
            helper.assertTrue(ac.fateTargets().isEmpty()&&bc.fateTargets().isEmpty(),"Dead casters retained effective bindings");helper.succeed();
        }finally{CastChains.release(ac,owner);CastChains.release(bc,owner);a.discard();b.discard();item.discard();}
    }
    @GameTest(template="trickster_stock_test")
    public static void replayChangesTargetsOwnSavedAndActiveStacks(GameTestHelper helper) {
        var a=player(helper,false);var b=player(helper,true);Object owner=new Object();var ac=chain(a,owner);
        var image=new CastingImage(at.petrak.hexcasting.api.utils.TreeList.from(List.of(new at.petrak.hexcasting.api.casting.iota.DoubleIota(37))),0,at.petrak.hexcasting.api.utils.TreeList.empty(),false,false,0,new net.minecraft.nbt.CompoundTag());
        at.petrak.hexcasting.xplat.IXplatAbstractions.INSTANCE.setStaffcastImage(b,image);
        try {
            SharedFateManager.bind(a,b,ac,32000);incident(a,halfHealth());
            var saved=at.petrak.hexcasting.xplat.IXplatAbstractions.INSTANCE.getStaffcastVM(b,InteractionHand.MAIN_HAND).getImage().getStack();
            helper.assertTrue(saved.size()==2&&saved.get(0) instanceof at.petrak.hexcasting.api.casting.iota.DoubleIota,"Recipient's saved staff stack not changed");
            var active=new CastingVM(image,new StaffCastEnv(b,InteractionHand.MAIN_HAND));
            try(var tracked=SharedFateMishaps.enterVm(active)){incident(a,halfHealth());}
            helper.assertTrue(active.getImage().getStack().size()==2,"Recipient active VM stack not changed");
            helper.succeed();
        }finally{CastChains.release(ac,owner);b.discard();}
    }
    @GameTest(template="trickster_stock_test")
    public static void bindsSameFragmentAndRejectsWithoutPaying(GameTestHelper helper) {
        var player=player(helper,false);var cow=helper.spawn(EntityType.COW,new BlockPos(1,1,1));
        Object owner=new Object();var chain=chain(player,owner);long before=BatteryAnima.available(player);
        try(var ignored=CastChains.enter(chain)) {
            var ctx=new SpellContext(new ExecutionState(List.of()),new PlayerSpellSource(player),new TickData());
            var target=EntityFragment.from(cow);var result=GreatTricks.SHARED_FATE.activate(ctx,List.of(target));
            helper.assertTrue(result==target,"Shared Fate replaced its input EntityFragment");
            helper.assertTrue(BatteryAnima.available(player)==before-32000,"Shared Fate did not charge 32 Anima");
            boolean rejected=false;
            try{GreatTricks.SHARED_FATE.activate(ctx,List.of(target));}catch(SharedFateBlunder expected){rejected=true;}
            helper.assertTrue(rejected&&BatteryAnima.available(player)==before-32000,"Duplicate target consumed soul");
            rejected=false;try{SharedFateManager.bind(player,player,chain,32000);}catch(SharedFateBlunder expected){rejected=true;}
            helper.assertTrue(rejected&&BatteryAnima.available(player)==before-32000,"Self binding consumed soul");
            var savedImage=new CastingImage();savedImage.getUserData().putString("cmi_cast_chain",chain.encode());
            var targets=new net.minecraft.nbt.ListTag();targets.add(net.minecraft.nbt.StringTag.valueOf(cow.getUUID().toString()));
            savedImage.getUserData().put("cmi_shared_fate_targets",targets);
            at.petrak.hexcasting.xplat.IXplatAbstractions.INSTANCE.setStaffcastImage(player,savedImage);
            cow.setPos(player.getX()+100,player.getY(),player.getZ());SharedFateManager.refresh(helper.getLevel().getServer());
            helper.assertTrue(chain.fateTargets().isEmpty(),"Leaving ambit did not permanently detach target");
            var persisted=player.getPersistentData().getCompound(at.petrak.hexcasting.forge.xplat.ForgeXplatImpl.TAG_VM).getCompound("userData");
            helper.assertTrue(persisted.getList("cmi_shared_fate_targets",net.minecraft.nbt.Tag.TAG_STRING).isEmpty(),"Detached target remained in staff save and could return after relog");helper.succeed();
        }finally{CastChains.release(chain,owner);}
    }
    @GameTest(template="trickster_stock_test")
    public static void cascadeIsDirectedAndDiamondTargetOnlyReceivesOnce(GameTestHelper helper) {
        var a=player(helper,true);var b=player(helper,true);var c=player(helper,true);var d=helper.spawn(EntityType.COW,new BlockPos(1,1,1));
        Object owner=new Object();var ac=chain(a,owner);var bc=chain(b,owner);var cc=chain(c,owner);
        try {
            SharedFateManager.bind(a,b,ac,32000);SharedFateManager.bind(a,c,ac,32000);
            SharedFateManager.bind(b,d,bc,32000);SharedFateManager.bind(c,d,cc,32000);
            boolean rejected=false;long before=BatteryAnima.available(b);
            try{SharedFateManager.bind(b,a,bc,32000);}catch(SharedFateBlunder expected){rejected=true;}
            helper.assertTrue(rejected&&BatteryAnima.available(b)==before,"Reverse cycle was accepted or charged");
            var context=new Mishap.Context(null,null);
            var vm=new CastingVM(new CastingImage(),new StaffCastEnv(a,InteractionHand.MAIN_HAND));
            new OperatorSideEffect.DoMishap(new MishapDivideByZero(net.minecraft.network.chat.Component.literal("1"),
                    net.minecraft.network.chat.Component.literal("0"),"divide"),context).performEffect(vm);
            helper.assertTrue(a.getHealth()==10&&b.getHealth()==10&&c.getHealth()==10&&d.getHealth()==5,
                    "Mishap did not replay once on each diamond recipient: "+a.getHealth()+", "+b.getHealth()+", "+c.getHealth()+", "+d.getHealth());
            new OperatorSideEffect.DoMishap(new MishapEntityNotFound(UUID.randomUUID(),null),context).performEffect(
                    new CastingVM(new CastingImage(),new StaffCastEnv(b,InteractionHand.MAIN_HAND)));
            helper.assertTrue(!a.hasEffect(net.minecraft.world.effect.MobEffects.CONFUSION),"B's incident travelled backwards to A");
            helper.succeed();
        }finally{CastChains.release(ac,owner);CastChains.release(bc,owner);CastChains.release(cc,owner);a.discard();b.discard();c.discard();}
    }
    @GameTest(template="trickster_stock_test")
    public static void restoredTargetsRevalidateWithoutAnimaPayment(GameTestHelper helper) {
        var player=player(helper,false);var cow=helper.spawn(EntityType.COW,new BlockPos(1,1,1));
        Object owner=new Object();var first=chain(player,owner);
        SharedFateManager.bind(player,cow,first,32000);
        var saved=CastChain.decode(first.encode());saved.restoreFateTargets(first.fateTargets().stream().map(UUID::toString).toList());
        SharedFateManager.suspend(helper.getLevel().getServer(),player.getUUID());first.suspend();
        long balance=BatteryAnima.available(player);var restored=chain(player,owner);
        // Resume an actual decoded chain, retaining legacy id and target metadata.
        CastChains.release(restored,owner);restored=CastChains.attach(saved,player,owner);
        try {
            helper.assertTrue(restored.fateTargets().contains(cow.getUUID()),"Saved target did not resume");
            helper.assertTrue(BatteryAnima.available(player)==balance,"Resume charged another 32 Anima");
            restored.toggle();SharedFateManager.refresh(helper.getLevel().getServer());
            helper.assertTrue(restored.fateTargets().isEmpty(),"Inverting reach failed to remove now-outside target");
            helper.succeed();
        }finally{CastChains.release(restored,owner);}
    }
}
