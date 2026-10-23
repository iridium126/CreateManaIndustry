package com.iridium126.createmanaindustry.client.ponder;

import static com.iridium126.createmanaindustry.client.ponder.Workshop.*;
import com.iridium126.createmanaindustry.CMIBlocks;
import com.iridium126.createmanaindustry.CMIFluids;
import com.iridium126.createmanaindustry.CMIItems;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;

public final class ProcessingPonderScenes {
    private ProcessingPonderScenes() {}

    static void press(Workshop w, BlockPos basin, boolean heated) {
        if (heated) w.burner(basin.below(), true);
        w.place(basin, AllBlocks.BASIN.get());
        w.place(basin.above(2), AllBlocks.MECHANICAL_PRESS.getDefaultState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
        w.drive(basin.above(2), Direction.Axis.X);
    }
    static void mixer(Workshop w, BlockPos basin, boolean heated) {
        if (heated) w.burner(basin.below(), true);
        w.place(basin, AllBlocks.BASIN.get());
        w.place(basin.above(2), AllBlocks.MECHANICAL_MIXER.get());
        w.cogDrive(basin.above(2));
    }
    static void lid(Workshop w, BlockPos basin, boolean open) {
        w.place(basin.above(), CMIBlocks.DEPOSITION_LID.getDefaultState()
                .setValue(BlockStateProperties.HALF, Half.BOTTOM)
                .setValue(BlockStateProperties.OPEN, open)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
    }

    public static void mediaConversion(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "media_conversion", "From Media to Mana", "将液态媒质转化为液态魔力");
        BlockPos basin = p(4, 2, 3);
        mixer(w, basin, true);
        w.text(70, basin, "Converting Liquid Media into Liquid Mana requires Heated mixing.", "液态媒质可以通过普通加热搅拌转化为液态魔力");
        w.explain(60, basin, "Each batch consumes 250 mB of Liquid Media.", "每次加工需要 250 mB 液态媒质");
        w.use(basin, new ItemStack(CMIFluids.LIQUID_MEDIA.get().getBucket()));
        w.liquid(basin, CMIFluids.LIQUID_MEDIA.get(), 250);
        w.explain(60, basin, "Mixing produces 125 mB of Liquid Mana: half the input volume.", "搅拌后得到 125 mB 液态魔力");
        w.mix(basin.above(2));
        w.liquid(basin, CMIFluids.LIQUID_MANA.get(), 125);
        w.pipe(basin.east(), Direction.WEST, Direction.EAST);
        w.pump(basin.east(2), Direction.EAST);
        w.tank(basin.east(3), CMIFluids.LIQUID_MANA.get(), 125);
        w.liquid(basin, CMIFluids.LIQUID_MANA.get(), 0);
        w.text(60, basin.east(3), "Store it for mana-based machines and item filling.", "液态魔力可存入流体储罐，用于供能和灌注");
        w.finish();
    }

    public static void glass(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "mist_amethyst", "Growing amethyst in a Basin", "使用雾在工作盆中生成紫水晶");
        BlockPos basin = p(6, 2, 4), a = p(3, 2, 4);
        mixer(w, basin, true);
        w.text(60, basin.below(), "This recipe needs Allay Heat and Liquid Media mist at the same time.", "此配方需要悦灵加热和液态媒质雾");
        MistPonderScenes.atomizer(w, a, CMIFluids.LIQUID_MEDIA.get());
        Workshop.Mist mist = w.mist(a, MistPonderScenes.MEDIA, 3);
        w.text(70, a, "Supply Media mist with an atomizer. The burner's own Soul mist is a different fluid.", "雾化器需要输入液态媒质，燃烧室产生的灵魂雾无法代替");
        w.explain(70, basin, "Add 32 Glass and one Iron Nugget to the Basin.", "工作盆中需要放入 32 个玻璃和 1 个铁粒");
        w.basinItems(basin, new ItemStack(Items.GLASS, 32), new ItemStack(Items.IRON_NUGGET));
        w.use(basin, new ItemStack(Items.GLASS, 32));
        w.explain(60, basin, "With both requirements met, mixing produces eight Amethyst Blocks.", "满足条件后，搅拌可以得到 8 个紫水晶块");
        w.mix(basin.above(2));
        w.basinItems(basin, new ItemStack(Items.AMETHYST_BLOCK, 8));
        w.scene.effects().indicateSuccess(basin);
        w.point(basin, PonderPalette.GREEN);
        w.text(60, basin, "Keep the Basin close enough to the atomizer for the mist to be concentrated enough.", "工作盆需要靠近雾源，以满足配方的浓度要求");
        w.waitForText();
        w.mistOff(mist); w.finish();
    }

    public static void amethystDeposition(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "amethyst_deposition", "Coating a metal sheet", "制作紫水晶镀层板");
        BlockPos basin = p(6, 2, 4), a = p(3, 2, 4);
        w.place(basin.below(), Blocks.ANDESITE);
        w.place(basin, AllBlocks.BASIN.get());
        w.basinItems(basin, AllItems.IRON_SHEET.asStack());
        w.text(60, basin, "Put an Iron Sheet into a Basin to make an amethyst-coated sheet.", "铁板可以在工作盆中镀上紫水晶");
        w.explain(70, basin.above(), "Place a Framed Glass Trapdoor in the lower half of the block directly above the Basin.", "在工作盆正上方放置边框玻璃活板门，并使其位于下半格");
        w.use(basin.above(), AllBlocks.FRAMED_GLASS_TRAPDOOR.asStack());
        lid(w, basin, true);
        MistPonderScenes.atomizer(w, a, CMIFluids.LIQUID_MEDIA.get());
        Workshop.Mist mist = w.mist(a, MistPonderScenes.MEDIA, 3);
        w.text(60, basin, "Surround the Basin with Liquid Media mist. This coating does not need a heater.", "此镀层需要液态媒质雾，无须额外加热");
        w.explain(60, basin.above(), "Close the lid to begin deposition. Opening it interrupts the process.", "关闭活板门可以开始沉积，打开时会中断加工");
        w.use(basin.above(), ItemStack.EMPTY);
        lid(w, basin, false);
        w.idle(20);
        w.basinItems(basin, CMIItems.AMETHYST_DEPOSITED_IRON_SHEET.asStack());
        w.waitForText();
        lid(w, basin, true);
        w.basinItems(basin);
        w.depot(p(6, 1, 2), CMIItems.AMETHYST_DEPOSITED_IRON_SHEET.asStack());
        w.text(60, p(6, 1, 2), "The coated sheet is ready. Copper, gold and brass sheets use the same method.", "铜板、金板和黄铜板也可以使用相同方法加工");
        w.waitForText();
        w.mistOff(mist); w.finish();
    }

    public static void roseDeposition(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "rose_deposition", "Rose Quartz Coating Line", "制作玫瑰石英镀层板");
        BlockPos source = p(3, 2, 4), target = p(6, 2, 3);
        w.burner(source.below(), true); w.place(source, AllBlocks.BASIN.get());
        w.liquid(source, CMIFluids.MOLTEN_ROSE_QUARTZ.get(), 125);
        lid(w, source, false);
        w.text(60, source, "Start with Molten Rose Quartz in a covered Basin above a lit Allay Burner.", "带盖工作盆中的熔融玫瑰石英需要悦灵加热");
        w.explain(70, source, "Vaporizing turns the molten liquid into mist. An Atomizer cannot do this job.", "汽化可以将熔融流体转化为雾，雾化器无法代替此步骤");
        w.liquid(source, CMIFluids.MOLTEN_ROSE_QUARTZ.get(), 0);
        Workshop.Mist mist = w.mist(source, MistPonderScenes.ROSE, 3);
        w.burner(target.below(), true); w.place(target, AllBlocks.BASIN.get());
        w.basinItems(target, AllItems.IRON_SHEET.asStack()); lid(w, target, true);
        w.text(70, target, "Place an Iron Sheet in a second Basin inside the mist, with heat underneath.", "在雾中放置另一个工作盆，加入铁板并在下方供热");
        w.explain(70, target, "Close its Framed Glass Trapdoor. Heat and rose quartz mist produce the coating.", "关闭边框玻璃活板门，可以利用热量和雾完成镀层");
        w.use(target.above(), ItemStack.EMPTY); lid(w, target, false);
        w.idle(20); w.basinItems(target, CMIItems.ROSE_QUARTZ_DEPOSITED_IRON_SHEET.asStack());
        w.waitForText();
        lid(w, target, true); w.basinItems(target);
        w.depot(p(6, 1, 1), CMIItems.ROSE_QUARTZ_DEPOSITED_IRON_SHEET.asStack());
        w.text(70, p(6, 1, 1), "The same process coats copper, gold and brass sheets. Keep supplying the vaporizing Basin.", "铜板、金板和黄铜板同样适用，汽化工作盆需要持续补液");
        w.waitForText();
        w.mistOff(mist); w.finish();
    }

    public static void prismarine(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "prismarine_quartz", "Prismarine quartz: solid, liquid, mist", "熔化与汽化海晶石英");
        BlockPos basin = p(4, 2, 3);
        w.depot(p(6, 1, 2), CMIItems.PRISMARINE_QUARTZ.asStack());
        w.text(70, p(6, 1, 2), "Craft Prismarine Quartz from one Quartz and eight Prismarine Shards.", "1 个石英和 8 个海晶碎片可以合成海晶石英");
        press(w, basin, true); w.basinItems(basin, CMIItems.PRISMARINE_QUARTZ.asStack());
        w.explain(70, basin, "Superheated compacting melts the crystal. A burning Allay Burner supplies enough heat.", "海晶石英需要超级加热冲压，悦灵燃烧室可以提供所需热量");
        w.press(basin.above(2), true); w.basinItems(basin);
        w.liquid(basin, CMIFluids.MOLTEN_PRISMARINE_QUARTZ.get(), 250);
        w.text(70, basin, "One crystal gives 250 mB of Molten Prismarine Quartz.", "每个海晶石英可得到 250 mB 熔融海晶石英");
        w.explain(80, basin.above(), "For mist, replace the press with a closed Framed Glass Trapdoor and keep Allay Heat below.", "将冲压机换成关闭的边框玻璃活板门，再提供悦灵加热，可以进行汽化");
        w.remove(basin.above(2)); w.remove(basin.above(2).west()); w.remove(basin.above(2).west(2));
        lid(w, basin, false);
        w.explain(70, basin, "Each vaporizing operation consumes 125 mB. Molten fluids cannot be fed to an Atomizer.", "每次汽化消耗 125 mB，熔融流体无法直接输入雾化器");
        w.liquid(basin, CMIFluids.MOLTEN_PRISMARINE_QUARTZ.get(), 125);
        Workshop.Mist mist = w.mist(basin, 0x59D4CC, 3);
        w.waitForText();
        w.mistOff(mist); w.finish();
    }

    public static void roseQuartz(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "rose_quartz", "Preparing rose quartz vapor", "熔化与汽化玫瑰石英");
        BlockPos basin = p(4, 2, 3);
        press(w, basin, true);
        w.basinItems(basin, AllItems.ROSE_QUARTZ.asStack());
        w.point(basin.below(), PonderPalette.GREEN);
        w.explain(70, basin.below(), "Rose Quartz melts by Superheated compacting. Allay Heat also meets this requirement.", "玫瑰石英需要超级加热冲压，悦灵加热也可满足要求");
        w.press(basin.above(2), true); w.basinItems(basin);
        w.liquid(basin, CMIFluids.MOLTEN_ROSE_QUARTZ.get(), 250);
        w.text(70, basin, "One Rose Quartz becomes 250 mB of molten liquid.", "每个玫瑰石英可得到 250 mB 熔融流体");
        w.explain(70, basin.above(), "Replace the press with a closed Framed Glass Trapdoor to vaporize it using Allay Heat.", "换用关闭的边框玻璃活板门和悦灵加热，可以进行汽化");
        w.remove(basin.above(2)); w.remove(basin.above(2).west()); w.remove(basin.above(2).west(2));
        lid(w, basin, false);
        w.explain(80, basin, "Each operation vaporizes 125 mB. Use this mist for rose quartz deposition nearby.", "每次汽化消耗 125 mB，产生的雾可用于附近的沉积加工");
        w.liquid(basin, CMIFluids.MOLTEN_ROSE_QUARTZ.get(), 125);
        Workshop.Mist mist = w.mist(basin, MistPonderScenes.ROSE, 3);
        w.waitForText();
        w.mistOff(mist); w.finish();
    }

    public static void coolant(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "coolant", "Ice for the cooling circuit", "制作冷却液");
        BlockPos basin = p(4, 1, 3);
        press(w, basin, false);
        w.text(60, basin, "Coolant is the liquid consumed by Condensers when they collect mist.", "冷凝管需要消耗冷却液以收集雾");
        w.explain(60, basin, "Compacting one block of Ice makes Coolant without any heating requirement.", "冰可以通过冲压制成冷却液，无须加热");
        w.basinItems(basin, new ItemStack(Items.ICE)); w.use(basin, new ItemStack(Items.ICE));
        w.press(basin.above(2), true); w.basinItems(basin);
        w.liquid(basin, CMIFluids.COOLANT.get(), 1000);
        w.text(60, basin, "One Ice becomes one bucket of Coolant.", "1 个冰可得到 1 桶冷却液");
        w.pipe(basin.east(), Direction.WEST, Direction.EAST); w.pump(basin.east(2), Direction.EAST);
        w.tank(basin.east(3), CMIFluids.COOLANT.get(), 1000); w.liquid(basin, CMIFluids.COOLANT.get(), 0);
        w.text(60, basin.east(3), "Store the result and pump it through your cooling circuit.", "使用管道可以将冷却液输入冷凝回路");
        w.finish();
    }

    public static void source(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "liquid_source", "A berry-powered supply", "制作液态魔源");
        BlockPos basin = p(4, 1, 3);
        mixer(w, basin, false);
        w.text(60, basin, "Sweet Berries and water can be mixed into Liquid Source.", "甜浆果和水可以搅拌为液态魔源");
        w.explain(70, basin, "Each batch mixes one Sweet Berry with 25 mB of water; no heat is needed.", "每次加工需要 1 个甜浆果和 25 mB 水，无须加热");
        w.basinItems(basin, new ItemStack(Items.SWEET_BERRIES));
        w.liquid(basin, net.minecraft.world.level.material.Fluids.WATER, 25);
        w.point(basin.above(2), PonderPalette.GREEN);
        w.mix(basin.above(2)); w.basinItems(basin);
        w.liquid(basin, CMIFluids.LIQUID_SOURCE.get(), 25);
        w.text(60, basin, "The finished batch contains 25 mB of Liquid Source.", "搅拌后得到 25 mB 液态魔源");
        w.pipe(basin.east(), Direction.WEST, Direction.EAST); w.pump(basin.east(2), Direction.EAST);
        w.tank(basin.east(3), CMIFluids.LIQUID_SOURCE.get(), 25); w.liquid(basin, CMIFluids.LIQUID_SOURCE.get(), 0);
        w.text(60, basin.east(3), "A berry farm and a steady water supply can keep this simple production line running.", "持续输入浆果和水，可以自动生产液态魔源");
        w.finish();
    }
}
