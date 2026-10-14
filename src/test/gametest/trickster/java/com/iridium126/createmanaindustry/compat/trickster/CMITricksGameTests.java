package com.iridium126.createmanaindustry.compat.trickster;

import com.iridium126.createmanaindustry.compat.trickster.blunder.InvalidKineticStressBlunder;
import com.iridium126.createmanaindustry.compat.trickster.blunder.InvalidKineticTargetBlunder;
import com.iridium126.createmanaindustry.compat.trickster.fragment.IotaFragment;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.content.kinetics.temporarykinetics.TemporaryKinetics;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.enjarai.trickster.block.ModBlocks;
import dev.enjarai.trickster.block.SpellConstructBlockEntity;
import dev.enjarai.trickster.item.ModItems;
import dev.enjarai.trickster.item.component.ModComponents;
import dev.enjarai.trickster.spell.Fragment;
import dev.enjarai.trickster.spell.Pattern;
import dev.enjarai.trickster.spell.SpellContext;
import dev.enjarai.trickster.spell.exception.blunder.WrappedBlunder;
import dev.enjarai.trickster.spell.execution.ExecutionState;
import dev.enjarai.trickster.spell.execution.TickData;
import dev.enjarai.trickster.spell.execution.source.BlockSpellSource;
import dev.enjarai.trickster.spell.fragment.FragmentType;
import dev.enjarai.trickster.spell.fragment.NumberFragment;
import dev.enjarai.trickster.spell.fragment.VectorFragment;
import dev.enjarai.trickster.spell.mana.ManaPool;
import dev.enjarai.trickster.spell.mana.SimpleManaPool;
import dev.enjarai.trickster.spell.mana.storage.ManaVariant;
import dev.enjarai.trickster.spell.mana.type.Manae;
import dev.enjarai.trickster.spell.trick.Trick;
import dev.enjarai.trickster.spell.trick.Tricks;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class CMITricksGameTests {
    @GameTest(template = "trickster_stock_test")
    public static void registeredIdsAndPatternLookupUseTheDeclaredInstances(GameTestHelper helper) {
        CMITricks.register();
        CMITricks.register();
        assertRegistered(helper, "temporary_kinetic_stress", Pattern.of(1, 0, 2, 3, 4, 5, 6, 8, 7),
                CMITricks.TEMPORARY_KINETIC_STRESS);
        assertRegistered(helper, "read_iota", Pattern.of(6, 7, 4, 1, 0, 3, 4, 5, 2, 1), CMITricks.Hexcasting.READ_IOTA);
        assertRegistered(helper, "eval_iota", Pattern.of(3, 6, 7, 8, 5, 4, 3, 0, 1, 4, 7, 3), CMITricks.Hexcasting.EVAL_IOTA);
        assertRegistered(helper, "mana_to_media", Pattern.of(3, 0, 4, 2), CMITricks.Hexcasting.MANA_TO_MEDIA);
        helper.assertTrue(FragmentType.REGISTRY.get(CreateManaIndustry.modLoc("iota")) == CMITricksterFragmentTypes.IOTA,
                "Hex iota fragment type was not registered before the bridge tricks");
        var original = new IotaFragment(IotaFragment.serializeIota(new at.petrak.hexcasting.api.casting.iota.DoubleIota(42)));
        helper.assertTrue(Fragment.fromBytes(original.toBytes()) instanceof IotaFragment decoded
                        && decoded.type() == CMITricksterFragmentTypes.IOTA && decoded.equals(original),
                "Registered iota fragment no longer serializes with Trickster's codec");
        helper.succeed();
    }

    @GameTest(template = "trickster_stock_test")
    public static void kineticStressUsesNativeSignatureAndPreservesItsEffect(GameTestHelper helper) {
        BlockPos sourcePos = new BlockPos(0, 1, 0);
        BlockPos targetPos = new BlockPos(1, 1, 1);
        helper.setBlock(sourcePos, ModBlocks.SPELL_CONSTRUCT);
        helper.setBlock(targetPos, AllBlocks.SHAFT.get());
        var source = (SpellConstructBlockEntity) helper.getBlockEntity(sourcePos);
        var knot = new ItemStack(ModItems.AMETHYST_KNOT);
        knot.set(ModComponents.MANA, knot.get(ModComponents.MANA).with(
                new SimpleManaPool(64000, 128000, ManaVariant.of(Manae.TRADITIONAL))));
        source.setItem(0, knot);
        var target = new VectorFragment(new Vector3d(helper.absolutePos(targetPos).getX(),
                helper.absolutePos(targetPos).getY(), helper.absolutePos(targetPos).getZ()));
        var context = new SpellContext(new ExecutionState(List.of()),
                new BlockSpellSource<>(helper.getLevel(), source.getBlockPos(), source), new TickData());
        var kinetic = (KineticBlockEntity) helper.getBlockEntity(targetPos);
        var trick = Tricks.lookup(Pattern.of(1, 0, 2, 3, 4, 5, 6, 8, 7));
        var result = trick.activate(context, List.of(target, new NumberFragment(-16), new NumberFragment(2.9)));
        long manaCost = (long) (ServerConfig.manaPerStress * 64 * 2
                * ServerConfig.kineticStressTrickManaMultiplier * ManaPool.MANA_SCALE);
        helper.assertTrue(result.equals(target) && TemporaryKinetics.getSpeed(kinetic) == -16
                        && TemporaryKinetics.getStress(kinetic) == 4,
                "Kinetic stress trick changed its return value, direction or capacity");
        helper.assertTrue(source.getItem(0).get(ModComponents.MANA).pool().get(helper.getLevel()) == 64000 - manaCost,
                "Kinetic stress trick changed its mana cost");
        helper.runAfterDelay(3, () -> {
            helper.assertTrue(!TemporaryKinetics.isActive(kinetic), "Fractional duration was not rounded down to two ticks");
            helper.succeed();
        });
    }

    @GameTest(template = "trickster_stock_test")
    public static void kineticStressRejectsInvalidTargetsAndMissingArguments(GameTestHelper helper) {
        BlockPos sourcePos = new BlockPos(0, 1, 0);
        helper.setBlock(sourcePos, ModBlocks.SPELL_CONSTRUCT);
        var source = (SpellConstructBlockEntity) helper.getBlockEntity(sourcePos);
        var ctx = new SpellContext(new ExecutionState(List.of()),
                new BlockSpellSource<>(helper.getLevel(), source.getBlockPos(), source), new TickData());
        var trick = CMITricks.TEMPORARY_KINETIC_STRESS;
        var absolutePos = helper.absolutePos(new BlockPos(1, 1, 1));
        var target = new VectorFragment(new Vector3d(absolutePos.getX(), absolutePos.getY(), absolutePos.getZ()));
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.STONE);
        assertBlunder(helper, () -> trick.activate(ctx, List.of(target, new NumberFragment(16), new NumberFragment(2))),
                InvalidKineticTargetBlunder.class);
        assertBlunder(helper, () -> trick.activate(ctx, List.of(target, new NumberFragment(0), new NumberFragment(2))),
                InvalidKineticStressBlunder.class);
        assertBlunder(helper, () -> trick.activate(ctx, List.of(target)),
                dev.enjarai.trickster.spell.exception.blunder.InvalidInputsBlunder.class);
        helper.succeed();
    }

    private static void assertRegistered(GameTestHelper helper, String path, Pattern pattern, Trick<?> expected) {
        helper.assertTrue(Tricks.REGISTRY.get(CreateManaIndustry.modLoc(path)) == expected
                        && Tricks.lookup(pattern) == expected,
                "Registered trick ID or pattern resolves to a different instance: " + path);
    }

    private static void assertBlunder(GameTestHelper helper, Runnable operation, Class<?> type) {
        try {
            operation.run();
            helper.fail("Expected Trickster blunder: " + type.getSimpleName());
        } catch (WrappedBlunder blunder) {
            helper.assertTrue(type.isInstance(blunder.blunder), "Unexpected Trickster blunder: " + blunder.blunder);
        }
    }

    private CMITricksGameTests() {}
}
