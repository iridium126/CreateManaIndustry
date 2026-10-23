package com.iridium126.createmanaindustry.client.ponder;

import static com.iridium126.createmanaindustry.client.ponder.Workshop.*;
import com.iridium126.createmanaindustry.CMIItems;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** BnB's native chain serialization, following .refs/Create-Bits-n-Bobs. */
public final class ChainPonderScenes {
    private ChainPonderScenes() {}
    public static void core(SceneBuilder builder, SceneBuildingUtil util) {
        Workshop w = new Workshop(builder, util, "kinetics_core", "Driving a spell core with a chain", "使用齿轮链驱动法术核心");
        BlockPos cog = p(3, 2, 3), construct = p(6, 2, 3);
        w.place(construct, block("trickster:modular_spell_construct").defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP));
        w.explain(60, construct, "Install a Kinetics Spell Core in a Modular Spell Construct.", "动力法术核心可以装入模块化法术组构台");
        w.use(construct, CMIItems.KINETICS_SPELL_CORE.asStack());
        TricksterPonderScenes.slot(w, construct, 1, CMIItems.KINETICS_SPELL_CORE.asStack());
        w.place(cog, AllBlocks.COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
        w.place(cog.below(), AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
        w.text(70, cog, "This connection uses the cogwheel chains provided by Bits 'n' Bobs.", "此连接需要使用 Bits ’n’ Bobs 的齿轮链");
        w.explain(70, construct, "Link the powered cogwheel to the core with a chain. Nearby rotation alone is not a connection.", "使用链条将动力齿轮连接到核心，邻近的齿轮不会自动连接");
        w.use(cog, new ItemStack(Items.CHAIN)); w.use(construct, new ItemStack(Items.CHAIN));
        w.scene.world().modifyBlockEntityNBT(util.select().position(cog), KineticBlockEntity.class, nbt -> {
            CompoundTag chain = new CompoundTag();
            chain.putInt("cogwheel_pos_count", 2);
            for (int i = 0; i < 2; i++) {
                CompoundTag node = new CompoundTag();
                node.putBoolean("Side", true); node.putBoolean("IsLarge", false);
                node.putBoolean("OffsetForSmallCogwheel", i == 0);
                node.putInt("OffsetX", i * 3); node.putInt("OffsetY", 0); node.putInt("OffsetZ", 0);
                node.putInt("RotationAxis", Direction.Axis.Y.ordinal());
                chain.put("cogwheel_pos_" + i, node);
            }
            nbt.put("Chain", chain); nbt.putInt("ChainsToRefund", 6);
        });
        w.explain(70, construct, "While the connected chain turns, the core can execute its spell. More speed raises its execution allowance.", "齿轮链转动时，核心可以执行法术，提高转速可增加执行额度");
        w.speed(cog.below(), 32); w.speed(cog, 32);
        w.scene.effects().rotationSpeedIndicator(cog); w.point(construct, PonderPalette.GREEN);
        w.explain(60, construct, "Stop the chain and this core's execution allowance becomes zero. Restore rotation to resume.", "齿轮链停止转动后，执行额度归零");
        w.speed(cog.below(), 0); w.speed(cog, 0); w.point(construct, PonderPalette.RED);
        w.finish();
    }
}
