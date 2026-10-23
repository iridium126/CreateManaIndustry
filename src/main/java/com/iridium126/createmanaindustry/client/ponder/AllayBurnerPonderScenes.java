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
        w.text(b, "An Allay Burner needs an Allay and a supply of fuel.", "悦灵燃烧室需要一只悦灵，还需要持续补充燃料。");
        w.use(b, CMIBlocks.EMPTY_ALLAY_BURNER.asStack());
        w.text(b, "Hold an empty burner and right-click an Allay to capture it.", "手持空悦灵燃烧室，右键点击悦灵即可捕获。");
        w.scene.world().modifyEntities(Allay.class, Entity::discard);
        w.depot(p(6, 1, 3), CMIBlocks.ALLAY_BURNER.asStack());
        w.text(p(6, 1, 3), "The filled burner is now ready to place in your workstation.", "装入悦灵后，就可以把燃烧室放进工作站了。");
        w.display(p(6, 1, 3), ItemStack.EMPTY);
        w.burner(b, false);
        w.point(b, PonderPalette.RED);
        w.text(b, "Capturing an Allay does not light the burner. Feed it first.", "捕获悦灵不会自动点火，先给它添加燃料。");
        w.use(b, new ItemStack(Items.AMETHYST_SHARD));
        w.light(b, true);
        w.text(b, "An Amethyst Shard starts the fire. You can now use its heat.", "喂入紫水晶碎片后，燃烧室就能提供热量。");
        w.finish();
    }

    public static void solidFuel(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "allay_fuel", "Keeping the Allay Burner Burning", "让燃烧室持续燃烧");
        BlockPos b = p(5, 1, 3), depot = p(3, 1, 2), arm = p(4, 1, 4);
        w.burner(b, false);
        w.text(b, "Feed the Allay Burner with Amethyst Shards to provide heat.", "给悦灵燃烧室喂入紫水晶碎片，就能开始供热。");
        w.use(b, new ItemStack(Items.AMETHYST_SHARD));
        w.light(b, true);
        w.text(b, "More fuel adds more burning time. It does not create a hotter tier.", "继续添加燃料可以延长燃烧时间，不会提高火力等级。");
        w.depot(depot, new ItemStack(Items.AMETHYST_SHARD));
        w.point(depot, PonderPalette.INPUT);
        w.point(b, PonderPalette.OUTPUT);
        w.arm(arm, depot, b, "createmanaindustry:allay_burner");
        w.text(b, "Allay Burners are Mechanical Arm targets. A configured arm can feed them Amethyst Shards.", "悦灵燃烧室支持机械臂投料，配置好的机械臂可以向它喂入紫水晶碎片。");
        w.armTransfer(arm, depot, new ItemStack(Items.AMETHYST_SHARD));
        w.light(b, true);
        w.text(arm, "Keep shards on the Depot and the arm can replenish the burner.", "持续向置物台补充碎片，机械臂就能自动添料。");
        w.finish();
    }

    public static void liquidMedia(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "allay_liquid", "Using Liquid Fuel", "使用液态燃料");
        BlockPos b = p(6, 1, 3), tank = p(2, 1, 3);
        w.burner(b, false);
        w.use(b, new ItemStack(CMIFluids.LIQUID_MEDIA.get().getBucket()));
        w.liquid(b, CMIFluids.LIQUID_MEDIA.get(), 1000);
        w.light(b, true);
        w.text(b, "A bucket of Liquid Media can also fuel the burner.", "一桶液态媒质也能为燃烧室供能。");
        w.tank(tank, CMIFluids.LIQUID_MEDIA.get(), 4000);
        w.pipe(p(3, 1, 3), Direction.WEST, Direction.EAST);
        w.pump(p(4, 1, 3), Direction.EAST);
        w.pipe(p(5, 1, 3), Direction.WEST, Direction.EAST);
        w.liquid(tank, CMIFluids.LIQUID_MEDIA.get(), 3000);
        w.text(b, "The internal tank holds one bucket. Pipes can insert Liquid Media, but cannot extract it.", "内置储罐能装一桶液态媒质。管道可以灌入，但不能抽出。");
        w.use(b, new ItemStack(Items.AMETHYST_SHARD));
        w.text(b, "Solid fuel is used first. Liquid Media waits until that fuel runs out.", "固态燃料优先消耗，耗尽后才会继续使用液态媒质。");
        w.point(tank, PonderPalette.INPUT);
        w.text(tank, "Keep the supply topped up for unattended heating.", "保持储罐中有液态媒质，就能持续自动供热。");
        w.finish();
    }

    public static void heatLevels(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "allay_heat_levels", "Heat level of Allay Burner", "悦灵燃烧室的热量");
        BlockPos b = p(4, 1, 3), basin = b.above();
        w.place(b, CMIBlocks.ALLAY_BURNER.getDefaultState().setValue(AllayBurnerBlock.HEAT_LEVEL, AllayBurnerBlock.HeatLevel.NONE));
        w.text(b, "An empty burner has no Allay and supplies no processing heat.", "空燃烧室没有悦灵，无法提供加工热量。");
        w.remove(b); w.burner(b, false);
        w.text(b, "A captured Allay without fuel stays idle. This state also supplies no heat.", "装入悦灵但没有燃料时，燃烧室处于待机状态，仍然不能供热。");
        w.use(b, new ItemStack(Items.AMETHYST_SHARD)); w.light(b, true);
        w.text(b, "Both fuel types give Allay Heat. Extra fuel extends burning time; running out returns the burner to idle.", "固态与液态燃料都提供悦灵加热；追加燃料只延长时间，燃料耗尽后回到待机。");
        w.light(b, false); w.scene.idle(20);
        w.use(b, new ItemStack(CMIFluids.LIQUID_MEDIA.get().getBucket()));
        w.liquid(b, CMIFluids.LIQUID_MEDIA.get(), 1000); w.light(b, true);
        w.scene.idle(20);

        w.clearWorkArea();
        ProcessingPonderScenes.mixer(w, basin, true);
        w.liquid(basin, CMIFluids.LIQUID_MEDIA.get(), 250);
        w.mix(basin.above(2));
        w.liquid(basin, CMIFluids.LIQUID_MANA.get(), 125);
        w.text(basin, "Allay Heat covers ordinary Heated recipes: mix 250 mB of Liquid Media into 125 mB of Liquid Mana.", "悦灵加热兼容普通加热：250 mB 液态媒质可搅拌为 125 mB 液态魔力。");

        w.clearWorkArea();
        ProcessingPonderScenes.press(w, basin, true);
        w.basinItems(basin, AllItems.ROSE_QUARTZ.asStack());
        w.press(basin.above(2), true); w.basinItems(basin);
        w.liquid(basin, CMIFluids.MOLTEN_ROSE_QUARTZ.get(), 250);
        w.text(basin, "It also covers Superheated recipes: one Rose Quartz melts into 250 mB of molten liquid.", "它也兼容超级加热：1 个玫瑰石英可冲压熔化为 250 mB 熔融液体。");

        w.clearWorkArea();
        w.place(b, AllBlocks.BLAZE_BURNER.getDefaultState().setValue(BlazeBurnerBlock.HEAT_LEVEL, BlazeBurnerBlock.HeatLevel.SEETHING));
        w.place(basin, AllBlocks.BASIN.get());
        w.liquid(basin, CMIFluids.MOLTEN_ROSE_QUARTZ.get(), 125);
        ProcessingPonderScenes.lid(w, basin, false);
        w.point(b, PonderPalette.RED);
        // No liquid consumption or mist on this failed, superheated attempt.
        w.text(b, "Allay Heat is the next tier above Superheated. A superheated Blaze Burner cannot vaporize this liquid.", "悦灵加热是超级加热之上的一档。超级加热的烈焰人燃烧室仍无法汽化这里的液体。");
        w.burner(b, true);
        w.liquid(basin, CMIFluids.MOLTEN_ROSE_QUARTZ.get(), 0);
        Workshop.Mist mist = w.mist(basin, MistPonderScenes.ROSE, 3);
        w.point(b, PonderPalette.GREEN);
        w.text(basin, "A burning Allay Burner vaporizes the 125 mB. Its Allay Heat covers Heated, Superheated and Allay-heated recipes.", "换成燃烧中的悦灵燃烧室，125 mB 液体即可汽化。它能满足普通加热、超级加热和悦灵加热要求。");
        w.mistOff(mist); w.finish();
    }

    public static void heating(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "allay_heating", "Producing Liquid Media from Amethyst", "从紫水晶生产液态媒质");
        BlockPos b = p(4, 1, 3), basin = b.above(), press = b.above(3);
        w.burner(b, false);
        w.place(basin, AllBlocks.BASIN.get());
        w.place(press, AllBlocks.MECHANICAL_PRESS.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
        w.drive(press, Direction.Axis.X);
        w.text(basin, "Heated compacting turns Amethyst Shards into Liquid Media.", "加热冲压可以把紫水晶碎片转为液态媒质。");
        w.basinItems(basin, new ItemStack(Items.AMETHYST_SHARD));
        w.use(basin, new ItemStack(Items.AMETHYST_SHARD));
        w.text(basin, "The shard in the Basin is the ingredient; the burner below needs its own fuel.", "工作盆里的碎片是加工原料，下方燃烧室还需要单独供给燃料。");
        w.use(b, new ItemStack(Items.AMETHYST_SHARD));
        w.light(b, true);
        w.press(press, true);
        w.basinItems(basin);
        w.liquid(basin, CMIFluids.LIQUID_MEDIA.get(), 125);
        w.text(basin, "The press turns the shard into Liquid Media. Its yield follows the server configuration.", "冲压后得到液态媒质，实际产量由服务器配置决定。");
        w.pipe(p(5, 2, 3), Direction.WEST, Direction.EAST);
        w.pump(p(6, 2, 3), Direction.EAST);
        w.tank(p(7, 2, 3), CMIFluids.LIQUID_MEDIA.get(), 125);
        w.liquid(basin, CMIFluids.LIQUID_MEDIA.get(), 0);
        w.text(p(7, 2, 3), "Pump the result into storage or another machine.", "用泵把产物抽进储罐，或送往下一台机器。");
        w.finish();
    }

    public static void music(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "allay_music", "Music beside the machinery", "机器旁的音乐角");
        BlockPos b = p(4, 1, 3), shelf = p(6, 1, 3);
        w.burner(b, false);
        w.depot(shelf, new ItemStack(Items.MUSIC_DISC_CAT));
        w.text(b, "The Allay Burner also works as a jukebox.", "悦灵燃烧室也可以当作唱片机使用。");
        w.use(b, new ItemStack(Items.MUSIC_DISC_CAT));
        w.display(shelf, ItemStack.EMPTY);
        // Store the record without starting a world-level sound that could outlive the scene.
        w.scene.world().modifyBlockEntityNBT(util.select().position(b), AllayBurnerBlockEntity.class,
                nbt -> nbt.put("RecordItem", new ItemStack(Items.MUSIC_DISC_CAT).saveOptional(w.scene.world().getHolderLookupProvider())));
        w.scene.world().modifyBlock(b, s -> s.setValue(AllayBurnerBlock.HAS_RECORD, true), false);
        w.text(b, "Right-click with a music disc to insert it, just like a jukebox.", "手持唱片右键插入，操作与唱片机相同。");
        w.point(b, PonderPalette.RED);
        w.text(b, "A music disc is not fuel. Music alone does not heat a Basin.", "唱片不是燃料，只放音乐不会给工作盆供热。");
        w.use(b, new ItemStack(Items.AMETHYST_SHARD));
        w.light(b, true);
        w.text(b, "You can feed the burner while the disc is inside.", "唱片留在里面时，也可以照常添加燃料。");
        w.use(b, ItemStack.EMPTY);
        w.scene.world().modifyBlockEntityNBT(util.select().position(b), AllayBurnerBlockEntity.class, nbt -> nbt.remove("RecordItem"));
        w.scene.world().modifyBlock(b, s -> s.setValue(AllayBurnerBlock.HAS_RECORD, false), false);
        w.display(shelf, new ItemStack(Items.MUSIC_DISC_CAT));
        w.text(b, "Right-click with an empty hand to take the disc out again.", "空手右键即可取出唱片。");
        w.finish();
    }
}
