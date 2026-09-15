package com.iridium126.createmanaindustry.datagen;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.tterrag.registrate.builders.BlockBuilder;
import com.tterrag.registrate.providers.ProviderType;
import com.tterrag.registrate.util.nullness.NonNullFunction;

import net.minecraft.advancements.critereon.EnchantmentPredicate;
import net.minecraft.advancements.critereon.ItemEnchantmentsPredicate;
import net.minecraft.advancements.critereon.ItemPredicate;
import net.minecraft.advancements.critereon.ItemSubPredicates;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.predicates.AnyOfCondition;
import net.minecraft.world.level.storage.loot.predicates.MatchTool;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.neoforged.neoforge.common.conditions.ICondition;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.common.loot.CanItemPerformAbility;

/**
 * Small, project-owned extensions for Create's Registrate instance.
 *
 * <p>Registrate's built-in loot provider serializes a {@link LootTable} but has
 * no way to attach a top-level NeoForge condition. This class adds that missing
 * hook without changing the external Registrate dependency. It also exposes
 * optional tag and block-model helpers that keep optional content safe when its
 * dependency is absent.</p>
 */
public final class CMIRegistrateExtensions {
    /** Definitions collected while the Registrate builders are declared. */
    private static final Map<ResourceLocation, ConditionalLootTableProvider.Definition> CONDITIONAL_LOOT =
            new LinkedHashMap<>();

    private CMIRegistrateExtensions() {
    }

    /**
     * Attach a condition-aware loot-table definition to a block builder.
     *
     * <p>Registrate's block loot provider validates every registered block and
     * therefore needs a callback even when this extension owns the final table.
     * A no-drop placeholder satisfies that validation; the top-level CMI
     * provider writes the condition-aware table afterwards.</p>
     */
    public static <T extends Block, P> BlockBuilder<T, P> conditionalLoot(
            BlockBuilder<T, P> builder,
            Function<HolderLookup.Provider, LootTable.Builder> tableFactory,
            ICondition... conditions) {
        builder.loot((provider, block) -> provider.add(block, LootTable.lootTable()));
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                builder.getOwner().getModid(), builder.getName());
        synchronized (CONDITIONAL_LOOT) {
            if (CONDITIONAL_LOOT.put(id,
                    new ConditionalLootTableProvider.Definition(id, tableFactory, List.of(conditions))) != null) {
                throw new IllegalStateException("Duplicate conditional loot table: " + id);
            }
        }
        return builder;
    }

    static List<ConditionalLootTableProvider.Definition> conditionalLootDefinitions() {
        synchronized (CONDITIONAL_LOOT) {
            return List.copyOf(CONDITIONAL_LOOT.values());
        }
    }

    /**
     * Add this block as an optional member of one or more vanilla tags. The
     * generated entry contains {@code required:false}, so the tag remains valid
     * when the block is not registered because its optional dependency is absent.
     */
    @SafeVarargs
    public static <T extends Block, P> BlockBuilder<T, P> optionalBlockTags(
            BlockBuilder<T, P> builder, TagKey<Block>... tags) {
        return builder.setData(ProviderType.BLOCK_TAGS, (ctx, provider) -> {
            for (TagKey<Block> tag : tags) {
                provider.addTag(tag).addOptional(ctx.getId());
            }
        });
    }

    /**
     * Generate the standard leaves blockstate and model pair used by vanilla
     * and Hexcasting: {@code minecraft:block/leaves} with cutout-mipped render
     * type and one configurable texture.
     */
    public static <T extends Block, P> NonNullFunction<BlockBuilder<T, P>, BlockBuilder<T, P>> leavesModel(
            String texturePath) {
        return builder -> builder.blockstate((ctx, provider) -> {
            var model = provider.models()
                    .withExistingParent(ctx.getName(), ResourceLocation.parse("minecraft:block/leaves"))
                    .renderType("minecraft:cutout_mipped")
                    .texture("all", provider.modLoc(texturePath));
            provider.simpleBlock(ctx.getEntry(), model);
        });
    }

    /** Convenience transform for the two standard vanilla tags used by leaves. */
    public static <T extends Block, P> NonNullFunction<BlockBuilder<T, P>, BlockBuilder<T, P>> optionalLeavesTags() {
        return builder -> optionalBlockTags(builder, BlockTags.LEAVES, BlockTags.MINEABLE_WITH_HOE);
    }

    /** Convenience transform for the common Hexcasting condition. */
    public static ICondition hexcastingCondition() {
        return new net.neoforged.neoforge.common.conditions.ModLoadedCondition("hexcasting");
    }

    /**
     * Build the standard Hexcasting-style leaves drop table while keeping the
     * drop item a resource location. This avoids a compile-time dependency on
     * Hexcasting and is useful for any optional leaves-like block.
     */
    public static Function<HolderLookup.Provider, LootTable.Builder> leavesLoot(ResourceLocation dropItem) {
        return registries -> {
            var item = registries.lookupOrThrow(Registries.ITEM)
                    .getOrThrow(net.minecraft.resources.ResourceKey.create(Registries.ITEM, dropItem))
                    .value();
            // Datagen registry sets can expose delegate lookups whose holders
            // belong to their parent registry. A stand-alone reference bound
            // to this lookup keeps RegistryFixedCodec's owner check valid.
            var enchantmentLookup = registries.lookupOrThrow(Registries.ENCHANTMENT);
            var silkTouch = MatchTool.toolMatches(
                    ItemPredicate.Builder.item()
                            .withSubPredicate(ItemSubPredicates.ENCHANTMENTS,
                                    ItemEnchantmentsPredicate.enchantments(List.of(
                                            new EnchantmentPredicate(
                                                    Holder.Reference.createStandAlone(enchantmentLookup,
                                                            Enchantments.SILK_TOUCH),
                                                    net.minecraft.advancements.critereon.MinMaxBounds.Ints.atLeast(1))))));
            return LootTable.lootTable().withPool(LootPool.lootPool()
                    .setRolls(ConstantValue.exactly(1.0f))
                    .when(AnyOfCondition.anyOf(
                            CanItemPerformAbility.canItemPerformAbility(ItemAbilities.SHEARS_DIG),
                            silkTouch))
                    .add(LootItem.lootTableItem(item)));
        };
    }
}
