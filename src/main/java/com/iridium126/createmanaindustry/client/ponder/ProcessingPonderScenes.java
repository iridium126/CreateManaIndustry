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
        Workshop w = new Workshop(builder, util, "media_conversion", "From Media to Mana", "把媒质转成魔力");
        BlockPos basin = p(4, 2, 3);
        mixer(w, basin, true);
        w.text(basin, "Use a heated Basin and a powered Mechanical Mixer to convert Liquid Media.", "用有热源的工作盆和有动力的搅拌器，转换液态媒质。");
        w.use(basin, new ItemStack(CMIFluids.LIQUID_MEDIA.get().getBucket()));
        w.liquid(basin, CMIFluids.LIQUID_MEDIA.get(), 125);
        w.text(basin, "This recipe takes 125 mB of Liquid Media at a time.", "这个配方每次使用 125 mB 液态媒质。");
        w.mix(basin.above(2));
        w.liquid(basin, CMIFluids.LIQUID_MANA.get(), 125);
        w.text(basin, "After mixing, the same amount becomes Liquid Mana.", "搅拌完成后，得到等量的液态魔力。");
        w.pipe(basin.east(), Direction.WEST, Direction.EAST);
        w.pump(basin.east(2), Direction.EAST);
        w.tank(basin.east(3), CMIFluids.LIQUID_MANA.get(), 125);
        w.liquid(basin, CMIFluids.LIQUID_MANA.get(), 0);
        w.text(basin.east(3), "Store it for mana-based machines and item filling.", "把液态魔力储存起来，供机器使用或灌注物品。");
        w.text(basin, "Check the recipe's heat and fluid requirements before connecting the next production step.", "连接下一道工序前，先确认配方要求的热量和液体种类。");
        w.finish();
    }

    public static void glass(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "mist_amethyst", "Growing amethyst in a Basin", "在工作盆中生成紫水晶");
        BlockPos basin = p(6, 2, 4), a = p(3, 2, 4);
        mixer(w, basin, true);
        w.text(basin.below(), "This recipe needs Allay Heat and Liquid Media mist at the same time.", "这个配方需要同时满足悦灵加热和液态媒质雾两个条件。");
        MistPonderScenes.atomizer(w, a, CMIFluids.LIQUID_MEDIA.get());
        Workshop.Mist mist = w.mist(a, MistPonderScenes.MEDIA, 3);
        w.text(a, "Supply Media mist with an atomizer. The burner's own Soul mist is a different fluid.", "用雾化器供应媒质雾。燃烧室自身产生的是灵魂雾，不能代替它。");
        w.basinItems(basin, new ItemStack(Items.GLASS, 32), new ItemStack(Items.IRON_NUGGET));
        w.use(basin, new ItemStack(Items.GLASS, 32));
        w.text(basin, "Add 32 Glass and one Iron Nugget to the Basin.", "向工作盆放入 32 个玻璃和 1 个铁粒。");
        w.mix(basin.above(2));
        w.basinItems(basin, new ItemStack(Items.AMETHYST_BLOCK, 8));
        w.scene.effects().indicateSuccess(basin);
        w.text(basin, "With both requirements met, mixing produces eight Amethyst Blocks.", "满足两个条件后，搅拌就会产出 8 个紫水晶块。");
        w.point(basin, PonderPalette.GREEN);
        w.text(basin, "Keep the Basin close enough to the atomizer for the mist to be concentrated enough.", "让工作盆靠近雾化器，确保这里的雾浓度足够。");
        w.mistOff(mist); w.finish();
    }

    public static void amethystDeposition(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "amethyst_deposition", "Coating a metal sheet", "给金属板镀上紫水晶");
        BlockPos basin = p(6, 2, 4), a = p(3, 2, 4);
        w.place(basin.below(), Blocks.ANDESITE);
        w.place(basin, AllBlocks.BASIN.get());
        w.basinItems(basin, AllItems.IRON_SHEET.asStack());
        w.text(basin, "Put an Iron Sheet into a Basin to make an amethyst-coated sheet.", "把铁板放进工作盆，准备制作紫水晶镀层铁板。");
        w.use(basin.above(), AllBlocks.FRAMED_GLASS_TRAPDOOR.asStack());
        lid(w, basin, true);
        w.text(basin.above(), "Place a Framed Glass Trapdoor in the lower half of the block directly above the Basin.", "在工作盆正上方一格的下半部，安装框架玻璃活板门。");
        MistPonderScenes.atomizer(w, a, CMIFluids.LIQUID_MEDIA.get());
        Workshop.Mist mist = w.mist(a, MistPonderScenes.MEDIA, 3);
        w.text(basin, "Surround the Basin with Liquid Media mist. This coating does not need a heater.", "让液态媒质雾覆盖工作盆。紫水晶镀层不需要额外加热。");
        w.use(basin.above(), ItemStack.EMPTY);
        lid(w, basin, false);
        w.scene.idle(20);
        w.basinItems(basin, CMIItems.AMETHYST_DEPOSITED_IRON_SHEET.asStack());
        w.text(basin.above(), "Close the lid to begin deposition. Opening it interrupts the process.", "关上盖子就能开始沉积，打开盖子会中断加工。");
        lid(w, basin, true);
        w.basinItems(basin);
        w.depot(p(6, 1, 2), CMIItems.AMETHYST_DEPOSITED_IRON_SHEET.asStack());
        w.text(p(6, 1, 2), "The coated sheet is ready. Copper, gold and brass sheets use the same method.", "镀层铁板完成了！铜板、金板和黄铜板也使用同样的方法。");
        w.mistOff(mist); w.finish();
    }

    public static void roseDeposition(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "rose_deposition", "Rose Quartz Coating Line", "玫瑰石英镀层产线");
        BlockPos source = p(3, 2, 4), target = p(6, 2, 3);
        w.burner(source.below(), true); w.place(source, AllBlocks.BASIN.get());
        w.liquid(source, CMIFluids.MOLTEN_ROSE_QUARTZ.get(), 125);
        lid(w, source, false);
        w.text(source, "Start with Molten Rose Quartz in a covered Basin above a lit Allay Burner.", "把熔融玫瑰石英装进带盖工作盆，下方放置点燃的悦灵燃烧室。");
        w.liquid(source, CMIFluids.MOLTEN_ROSE_QUARTZ.get(), 0);
        Workshop.Mist mist = w.mist(source, MistPonderScenes.ROSE, 3);
        w.text(source, "Vaporizing turns the molten liquid into mist. An Atomizer cannot do this job.", "汽化配方把熔融液体转成雾。这个步骤不能用雾化器代替。");
        w.burner(target.below(), true); w.place(target, AllBlocks.BASIN.get());
        w.basinItems(target, AllItems.IRON_SHEET.asStack()); lid(w, target, true);
        w.text(target, "Place an Iron Sheet in a second Basin inside the mist, with heat underneath.", "在雾中另放一个工作盆，加入铁板，并在下方提供热量。");
        w.use(target.above(), ItemStack.EMPTY); lid(w, target, false);
        w.scene.idle(20); w.basinItems(target, CMIItems.ROSE_QUARTZ_DEPOSITED_IRON_SHEET.asStack());
        w.text(target, "Close its Framed Glass Trapdoor. Heat and rose quartz mist produce the coating.", "关上框架玻璃活板门，热量与玫瑰石英雾就能完成镀层。");
        lid(w, target, true); w.basinItems(target);
        w.depot(p(6, 1, 1), CMIItems.ROSE_QUARTZ_DEPOSITED_IRON_SHEET.asStack());
        w.text(p(6, 1, 1), "The same process coats copper, gold and brass sheets. Keep supplying the vaporizing Basin.", "同样可以加工铜板、金板和黄铜板，记得持续向汽化工作盆补液。");
        w.mistOff(mist); w.finish();
    }

    public static void prismarine(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "prismarine_quartz", "Prismarine quartz: solid, liquid, mist", "海晶石英：固体、液体与雾");
        BlockPos basin = p(4, 2, 3);
        w.depot(p(6, 1, 2), CMIItems.PRISMARINE_QUARTZ.asStack());
        w.text(p(6, 1, 2), "Craft Prismarine Quartz from one Quartz and eight Prismarine Shards.", "用 1 个石英和 8 个海晶碎片，合成海晶石英。");
        press(w, basin, true); w.basinItems(basin, CMIItems.PRISMARINE_QUARTZ.asStack());
        w.text(basin, "Superheated compacting melts the crystal. A burning Allay Burner supplies enough heat.", "通过超级加热冲压熔化晶体，点燃的悦灵燃烧室能提供所需热量。");
        w.press(basin.above(2), true); w.basinItems(basin);
        w.liquid(basin, CMIFluids.MOLTEN_PRISMARINE_QUARTZ.get(), 250);
        w.text(basin, "One crystal gives 250 mB of Molten Prismarine Quartz.", "每个海晶石英得到 250 mB 熔融海晶石英。");
        w.remove(basin.above(2)); w.remove(basin.above(2).west()); w.remove(basin.above(2).west(2));
        lid(w, basin, false);
        w.text(basin.above(), "For mist, replace the press with a closed Framed Glass Trapdoor and keep Allay Heat below.", "要产生雾，就把冲压机换成关上的框架玻璃活板门，并保持悦灵加热。");
        w.liquid(basin, CMIFluids.MOLTEN_PRISMARINE_QUARTZ.get(), 125);
        Workshop.Mist mist = w.mist(basin, 0x59D4CC, 3);
        w.text(basin, "Each vaporizing operation consumes 125 mB. Molten fluids cannot be fed to an Atomizer.", "每次汽化消耗 125 mB。熔融流体不能直接送入雾化器。");
        w.mistOff(mist); w.finish();
    }

    public static void roseQuartz(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "rose_quartz", "Preparing rose quartz vapor", "制备玫瑰石英雾");
        BlockPos basin = p(4, 2, 3);
        press(w, basin, true);
        w.basinItems(basin, AllItems.ROSE_QUARTZ.asStack());
        w.text(basin, "Put Rose Quartz into a Basin beneath a powered Mechanical Press.", "把玫瑰石英放进工作盆，上方安装有动力的冲压机。");
        w.point(basin.below(), PonderPalette.GREEN);
        w.text(basin.below(), "Melting needs superheat. A lit Allay Burner can provide it.", "熔化需要超级加热，点燃的悦灵燃烧室可以满足要求。");
        w.press(basin.above(2), true); w.basinItems(basin);
        w.liquid(basin, CMIFluids.MOLTEN_ROSE_QUARTZ.get(), 250);
        w.text(basin, "One Rose Quartz becomes 250 mB of molten liquid.", "1 个玫瑰石英可以熔化成 250 mB 液体。");
        w.remove(basin.above(2)); w.remove(basin.above(2).west()); w.remove(basin.above(2).west(2));
        lid(w, basin, false);
        w.text(basin.above(), "Replace the press with a closed Framed Glass Trapdoor to vaporize it using Allay Heat.", "将冲压机换成关闭的框架玻璃活板门，利用悦灵加热进行汽化。");
        w.liquid(basin, CMIFluids.MOLTEN_ROSE_QUARTZ.get(), 125);
        Workshop.Mist mist = w.mist(basin, MistPonderScenes.ROSE, 3);
        w.text(basin, "Each operation vaporizes 125 mB. Use this mist for rose quartz deposition nearby.", "每次汽化消耗 125 mB，产生的雾可供附近的玫瑰石英沉积加工使用。");
        w.mistOff(mist); w.finish();
    }

    public static void coolant(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "coolant", "Ice for the cooling circuit", "用冰制作冷却液");
        BlockPos basin = p(4, 1, 3);
        press(w, basin, false);
        w.text(basin, "Coolant is the liquid consumed by Condensers when they collect mist.", "冷凝管收集雾时，需要消耗冷却液。");
        w.basinItems(basin, new ItemStack(Items.ICE)); w.use(basin, new ItemStack(Items.ICE));
        w.text(basin, "Put one block of Ice into a Basin below a powered Mechanical Press.", "把 1 个冰放进工作盆，上方使用有动力的冲压机。");
        w.text(basin, "This compacting recipe needs no burner underneath.", "这个冲压配方不需要下方的燃烧室。");
        w.press(basin.above(2), true); w.basinItems(basin);
        w.liquid(basin, CMIFluids.COOLANT.get(), 1000);
        w.text(basin, "One Ice becomes one bucket of Coolant.", "1 个冰会产出 1 桶冷却液。");
        w.pipe(basin.east(), Direction.WEST, Direction.EAST); w.pump(basin.east(2), Direction.EAST);
        w.tank(basin.east(3), CMIFluids.COOLANT.get(), 1000); w.liquid(basin, CMIFluids.COOLANT.get(), 0);
        w.text(basin.east(3), "Store the result and pump it through your cooling circuit.", "将产物储存起来，再用泵送入冷凝回路。");
        w.finish();
    }

    public static void source(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "liquid_source", "A berry-powered supply", "用浆果生产液态魔源");
        BlockPos basin = p(4, 1, 3);
        mixer(w, basin, false);
        w.text(basin, "Sweet Berries and water can be mixed into Liquid Source.", "甜浆果和水可以搅拌成液态魔源。");
        w.basinItems(basin, new ItemStack(Items.SWEET_BERRIES));
        w.liquid(basin, net.minecraft.world.level.material.Fluids.WATER, 25);
        w.text(basin, "Each batch uses one Sweet Berry and 25 mB of water.", "每次加工使用 1 个甜浆果和 25 mB 水。");
        w.point(basin.above(2), PonderPalette.GREEN);
        w.text(basin.above(2), "Power the Mixer. This recipe does not need heat.", "给搅拌器接入动力即可，这个配方不需要加热。");
        w.mix(basin.above(2)); w.basinItems(basin);
        w.liquid(basin, CMIFluids.LIQUID_SOURCE.get(), 25);
        w.text(basin, "The finished batch contains 25 mB of Liquid Source.", "完成后得到 25 mB 液态魔源。");
        w.pipe(basin.east(), Direction.WEST, Direction.EAST); w.pump(basin.east(2), Direction.EAST);
        w.tank(basin.east(3), CMIFluids.LIQUID_SOURCE.get(), 25); w.liquid(basin, CMIFluids.LIQUID_SOURCE.get(), 0);
        w.text(basin.east(3), "A berry farm and a steady water supply can keep this simple production line running.", "连接浆果农场和稳定水源，就能持续运行这条简单的生产线。");
        w.finish();
    }
}
