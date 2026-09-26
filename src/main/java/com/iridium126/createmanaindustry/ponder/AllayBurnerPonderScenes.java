package com.iridium126.createmanaindustry.ponder;

import static com.iridium126.createmanaindustry.ponder.Workshop.*;
import com.iridium126.createmanaindustry.CMIBlocks;
import com.iridium126.createmanaindustry.CMIFluids;
import com.iridium126.createmanaindustry.content.burner.AllayBurnerBlock;
import com.iridium126.createmanaindustry.content.burner.AllayBurnerBlockEntity;
import com.simibubi.create.AllBlocks;
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
        w.text(p(6, 1, 3), "The filled burner is now ready to place in your workshop.", "装入悦灵后，就可以把燃烧室放进工坊了。");
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
        Workshop w = new Workshop(builder, util, "allay_fuel", "Keeping the workshop warm", "让工坊持续供热");
        BlockPos b = p(5, 1, 3), depot = p(3, 1, 2), arm = p(4, 1, 4);
        w.burner(b, false);
        w.text(b, "Feed the Allay Burner with Amethyst Shards to provide heat.", "给悦灵燃烧室喂入紫水晶碎片，就能开始供热。");
        w.use(b, new ItemStack(Items.AMETHYST_SHARD));
        w.light(b, true);
        w.text(b, "More fuel adds more burning time. It does not create a hotter tier.", "继续添加燃料可以延长燃烧时间，不会提高火力等级。");
        w.depot(depot, new ItemStack(Items.AMETHYST_SHARD));
        w.use(depot, AllBlocks.MECHANICAL_ARM.asStack());
        w.point(depot, PonderPalette.INPUT);
        w.text(depot, "Before placing a Mechanical Arm, mark this Depot as its input.", "放置机械臂之前，先把这个置物台选为取料点。");
        w.use(b, AllBlocks.MECHANICAL_ARM.asStack());
        w.point(b, PonderPalette.OUTPUT);
        w.arm(arm, depot, b, "createmanaindustry:allay_burner");
        w.text(b, "Select the burner as the output, then place and power the arm.", "把燃烧室选为送料点，再放下机械臂并接入动力。");
        w.armTransfer(arm, depot, new ItemStack(Items.AMETHYST_SHARD));
        w.light(b, true);
        w.text(arm, "Keep shards on the Depot and the arm can replenish the burner.", "持续向置物台补充碎片，机械臂就能自动添料。");
        w.finish();
    }

    public static void liquidMedia(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "allay_liquid", "Fuel through pipes", "用管道自动供料");
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
        w.text(p(4, 1, 3), "Connect a tank and a powered pump. Pump towards the burner.", "连接储罐和有动力的泵，让液体流向燃烧室。");
        w.liquid(tank, CMIFluids.LIQUID_MEDIA.get(), 3000);
        w.text(b, "The internal tank holds one bucket. Pipes can insert Liquid Media, but cannot extract it.", "内置储罐能装一桶液态媒质。管道可以灌入，但不能抽出。");
        w.use(b, new ItemStack(Items.AMETHYST_SHARD));
        w.text(b, "Solid fuel is used first. Liquid Media waits until that fuel runs out.", "固体燃料优先消耗，耗尽后才会继续使用液态媒质。");
        w.point(tank, PonderPalette.INPUT);
        w.text(tank, "Keep the supply topped up for unattended heating.", "保持储罐中有液态媒质，就能持续自动供热。");
        w.finish();
    }

    public static void heating(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "allay_heating", "Amethyst into Liquid Media", "把紫水晶加工成液态媒质");
        BlockPos b = p(4, 1, 3), basin = b.above(), press = b.above(3);
        w.burner(b, false);
        w.place(basin, AllBlocks.BASIN.get());
        w.text(basin, "Place a Basin directly above the burner to use its heat.", "把工作盆放在燃烧室正上方，让热量传给工作盆。");
        w.place(press, AllBlocks.MECHANICAL_PRESS.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
        w.drive(press, Direction.Axis.X);
        w.text(press, "Add a powered Mechanical Press, leaving one block between the press and Basin.", "上方安装有动力的冲压机，冲压机与工作盆之间留一格空间。");
        w.basinItems(basin, new ItemStack(Items.AMETHYST_SHARD));
        w.use(basin, new ItemStack(Items.AMETHYST_SHARD));
        w.text(basin, "Put an Amethyst Shard in the Basin. This recipe also needs heat.", "向工作盆放入紫水晶碎片。这个配方还需要热量。");
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
