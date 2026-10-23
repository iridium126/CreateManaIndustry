package com.iridium126.createmanaindustry.client.ponder;

import static com.iridium126.createmanaindustry.client.ponder.Workshop.*;
import com.iridium126.createmanaindustry.CMIBlocks;
import com.iridium126.createmanaindustry.CMIFluids;
import com.iridium126.createmanaindustry.content.burner.AllayBurnerBlock;
import com.iridium126.createmanaindustry.content.burner.AllayBurnerBlockEntity;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

public final class AllayBurnerPonderScenes {
    private AllayBurnerPonderScenes() {}

    public static void capture(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "allay_capture", "A home for an Allay", "为悦灵安个家");
        BlockPos b = p(4, 1, 3);
        w.scene.world().createEntity(level -> {
            Allay allay = EntityType.ALLAY.create(level);
            if (allay != null) { allay.setPos(Vec3.atCenterOf(b)); allay.setNoAi(true); }
            return allay;
        });
        w.text(60, b, "An Allay Burner needs an Allay and a supply of fuel.", "悦灵燃烧室需要装入悦灵并补充燃料");
        w.explain(60, b, "Hold an empty burner and right-click an Allay to capture it.", "手持空悦灵燃烧室右击悦灵，可以将其捕获");
        w.use(b, CMIBlocks.EMPTY_ALLAY_BURNER.asStack());
        w.scene.world().modifyEntities(Allay.class, Entity::discard);
        w.depot(p(6, 1, 3), CMIBlocks.ALLAY_BURNER.asStack());
        w.text(60, p(6, 1, 3), "The filled burner is now ready to place in your workstation.", "捕获悦灵后，便可放置燃烧室");
        w.display(p(6, 1, 3), ItemStack.EMPTY);
        w.burner(b, false);
        w.point(b, PonderPalette.RED);
        w.text(60, b, "Capturing an Allay does not light the burner. Feed it first.", "捕获悦灵不会自动点燃燃烧室");
        w.explain(60, b, "An Amethyst Shard starts the fire. You can now use its heat.", "喂给悦灵紫水晶碎片，可以使燃烧室提供热量");
        w.use(b, new ItemStack(Items.AMETHYST_SHARD));
        w.light(b, true);
        w.finish();
    }

    public static void solidFuel(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "allay_fuel", "Keeping the Allay Burner Burning", "为悦灵燃烧室补充燃料");
        BlockPos b = p(5, 1, 3), depot = p(3, 1, 2), arm = p(4, 1, 4);
        w.burner(b, false);
        w.explain(60, b, "Feed the Allay Burner with Amethyst Shards to provide heat.", "紫水晶碎片可以为悦灵燃烧室提供燃料");
        w.use(b, new ItemStack(Items.AMETHYST_SHARD));
        w.light(b, true);
        w.text(70, b, "More fuel adds more burning time. It does not create a hotter tier.", "补充燃料只会延长燃烧时间，不会提高热量等级");
        w.depot(depot, new ItemStack(Items.AMETHYST_SHARD));
        w.point(depot, PonderPalette.INPUT);
        w.point(b, PonderPalette.OUTPUT);
        w.arm(arm, depot, b, "createmanaindustry:allay_burner");
        w.explain(60, b, "Allay Burners are Mechanical Arm targets. A configured arm can feed them Amethyst Shards.", "动力臂可以向悦灵燃烧室补充紫水晶碎片");
        w.armTransfer(arm, depot, new ItemStack(Items.AMETHYST_SHARD));
        w.light(b, true);
        w.text(60, arm, "Keep shards on the Depot and the arm can replenish the burner.", "向置物台持续输入碎片，便可自动补充燃料");
        w.finish();
    }

    public static void liquidMedia(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "allay_liquid", "Using Liquid Fuel", "使用液态媒质供热");
        BlockPos b = p(6, 1, 3), tank = p(2, 1, 3);
        w.burner(b, false);
        w.explain(60, b, "A bucket of Liquid Media can also fuel the burner.", "液态媒质也可以为悦灵燃烧室提供燃料");
        w.use(b, new ItemStack(CMIFluids.LIQUID_MEDIA.get().getBucket()));
        w.liquid(b, CMIFluids.LIQUID_MEDIA.get(), 1000);
        w.light(b, true);
        w.tank(tank, CMIFluids.LIQUID_MEDIA.get(), 4000);
        w.pipe(p(3, 1, 3), Direction.WEST, Direction.EAST);
        w.pump(p(4, 1, 3), Direction.EAST);
        w.pipe(p(5, 1, 3), Direction.WEST, Direction.EAST);
        w.liquid(tank, CMIFluids.LIQUID_MEDIA.get(), 3000);
        w.text(70, b, "The internal tank holds one bucket. Pipes can insert Liquid Media, but cannot extract it.", "内置储罐可容纳 1 桶媒质，管道只能向其中输入流体");
        w.explain(70, b, "Solid fuel is used first. Liquid Media waits until that fuel runs out.", "燃烧室会优先消耗固态燃料，然后使用液态媒质");
        w.use(b, new ItemStack(Items.AMETHYST_SHARD));
        w.point(tank, PonderPalette.INPUT);
        w.text(60, tank, "Keep the supply topped up for unattended heating.", "持续输入液态媒质，便可使燃烧室持续供热");
        w.finish();
    }

    public static void heatLevels(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "allay_heat_levels", "Heat level of Allay Burner", "悦灵燃烧室的热量等级");
        BlockPos b = p(4, 1, 3), basin = b.above();
        w.place(b, CMIBlocks.ALLAY_BURNER.getDefaultState().setValue(AllayBurnerBlock.HEAT_LEVEL, AllayBurnerBlock.HeatLevel.NONE));
        w.text(60, b, "An empty burner has no Allay and supplies no processing heat.", "空燃烧室无法提供加工所需的热量");
        w.remove(b); w.burner(b, false);
        w.text(70, b, "A captured Allay without fuel stays idle. This state also supplies no heat.", "没有燃料时，悦灵燃烧室处于待机状态，无法供热");
        w.explain(90, b, "Both fuel types give Allay Heat. Extra fuel extends burning time; running out returns the burner to idle.", "固态和液态燃料都可提供悦灵加热，燃料耗尽后回到待机状态");
        w.use(b, new ItemStack(Items.AMETHYST_SHARD)); w.light(b, true);
        w.idle(25);
        w.light(b, false); w.idle(20);
        w.use(b, new ItemStack(CMIFluids.LIQUID_MEDIA.get().getBucket()));
        w.liquid(b, CMIFluids.LIQUID_MEDIA.get(), 1000); w.light(b, true);
        w.idle(20);

        w.clearWorkArea();
        ProcessingPonderScenes.mixer(w, basin, true);
        w.liquid(basin, CMIFluids.LIQUID_MEDIA.get(), 250);
        w.explain(90, basin, "Allay Heat covers ordinary Heated recipes: mix 250 mB of Liquid Media into 125 mB of Liquid Mana.", "悦灵加热可以满足普通加热配方的要求");
        w.mix(basin.above(2));
        w.liquid(basin, CMIFluids.LIQUID_MANA.get(), 125);
        w.idle(20);

        w.clearWorkArea();
        ProcessingPonderScenes.press(w, basin, true);
        w.basinItems(basin, AllItems.ROSE_QUARTZ.asStack());
        w.explain(90, basin, "It also covers Superheated recipes: one Rose Quartz melts into 250 mB of molten liquid.", "悦灵加热也可以满足超级加热配方的要求");
        w.press(basin.above(2), true); w.basinItems(basin);
        w.liquid(basin, CMIFluids.MOLTEN_ROSE_QUARTZ.get(), 250);
        w.idle(20);

        w.clearWorkArea();
        w.place(b, AllBlocks.BLAZE_BURNER.getDefaultState().setValue(BlazeBurnerBlock.HEAT_LEVEL, BlazeBurnerBlock.HeatLevel.SEETHING));
        w.place(basin, AllBlocks.BASIN.get());
        w.liquid(basin, CMIFluids.MOLTEN_ROSE_QUARTZ.get(), 125);
        ProcessingPonderScenes.lid(w, basin, false);
        w.point(b, PonderPalette.RED);
        // No liquid consumption or mist on this failed, superheated attempt.
        w.text(90, b, "Allay Heat is the next tier above Superheated. A superheated Blaze Burner cannot vaporize this liquid.", "悦灵加热高于超级加热，烈焰人燃烧室无法满足这一要求");
        w.explain(90, basin, "A burning Allay Burner vaporizes the 125 mB. Its Allay Heat covers Heated, Superheated and Allay-heated recipes.", "换用悦灵燃烧室，便可将 125 mB 熔融玫瑰石英汽化");
        w.burner(b, true);
        w.liquid(basin, CMIFluids.MOLTEN_ROSE_QUARTZ.get(), 0);
        Workshop.Mist mist = w.mist(basin, MistPonderScenes.ROSE, 3);
        w.point(b, PonderPalette.GREEN);
        w.waitForText();
        w.mistOff(mist); w.finish();
    }

    public static void heating(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "allay_heating", "Producing Liquid Media from Amethyst", "使用紫水晶碎片制作液态媒质");
        BlockPos b = p(4, 1, 3), basin = b.above(), press = b.above(3);
        w.burner(b, false);
        w.place(basin, AllBlocks.BASIN.get());
        w.place(press, AllBlocks.MECHANICAL_PRESS.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
        w.drive(press, Direction.Axis.X);
        w.text(60, basin, "Heated compacting turns Amethyst Shards into Liquid Media.", "紫水晶碎片可以通过加热冲压转化为液态媒质");
        w.explain(70, basin, "The shard in the Basin is the ingredient; the burner below needs its own fuel.", "工作盆中的碎片用于加工，燃烧室还需要单独补充燃料");
        w.basinItems(basin, new ItemStack(Items.AMETHYST_SHARD));
        w.use(basin, new ItemStack(Items.AMETHYST_SHARD));
        w.use(b, new ItemStack(Items.AMETHYST_SHARD));
        w.light(b, true);
        w.explain(60, basin, "The press turns the shard into Liquid Media. Its yield follows the server configuration.", "冲压后得到液态媒质，产量由服务器配置决定");
        w.press(press, true);
        w.basinItems(basin);
        w.liquid(basin, CMIFluids.LIQUID_MEDIA.get(), 125);
        w.pipe(p(5, 2, 3), Direction.WEST, Direction.EAST);
        w.pump(p(6, 2, 3), Direction.EAST);
        w.tank(p(7, 2, 3), CMIFluids.LIQUID_MEDIA.get(), 125);
        w.liquid(basin, CMIFluids.LIQUID_MEDIA.get(), 0);
        w.text(60, p(7, 2, 3), "Pump the result into storage or another machine.", "使用管道可以将产物输入储罐或其他机器");
        w.finish();
    }

    public static void music(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "allay_music", "Music beside the machinery", "机器旁的音乐角");
        BlockPos b = p(4, 1, 3), shelf = p(6, 1, 3);
        w.burner(b, false);
        w.depot(shelf, new ItemStack(Items.MUSIC_DISC_CAT));
        w.text(60, b, "The Allay Burner also works as a jukebox.", "悦灵燃烧室也可以用于播放唱片");
        w.explain(60, b, "Right-click with a music disc to insert it, just like a jukebox.", "手持唱片右击燃烧室，可以将其放入");
        w.use(b, new ItemStack(Items.MUSIC_DISC_CAT));
        w.display(shelf, ItemStack.EMPTY);
        // Store the record without starting a world-level sound that could outlive the scene.
        w.scene.world().modifyBlockEntityNBT(util.select().position(b), AllayBurnerBlockEntity.class,
                nbt -> nbt.put("RecordItem", new ItemStack(Items.MUSIC_DISC_CAT).saveOptional(w.scene.world().getHolderLookupProvider())));
        w.scene.world().modifyBlock(b, s -> s.setValue(AllayBurnerBlock.HAS_RECORD, true), false);
        w.point(b, PonderPalette.RED);
        w.text(70, b, "A music disc is not fuel. Music alone does not heat a Basin.", "唱片无法作为燃料，播放音乐不会为工作盆供热");
        w.explain(60, b, "You can feed the burner while the disc is inside.", "播放唱片时，仍可向燃烧室补充燃料");
        w.use(b, new ItemStack(Items.AMETHYST_SHARD));
        w.light(b, true);
        w.explain(60, b, "Right-click with an empty hand to take the disc out again.", "空手右击燃烧室，可以取出唱片");
        w.use(b, ItemStack.EMPTY);
        w.scene.world().modifyBlockEntityNBT(util.select().position(b), AllayBurnerBlockEntity.class, nbt -> nbt.remove("RecordItem"));
        w.scene.world().modifyBlock(b, s -> s.setValue(AllayBurnerBlock.HAS_RECORD, false), false);
        w.display(shelf, new ItemStack(Items.MUSIC_DISC_CAT));
        w.finish();
    }
}
