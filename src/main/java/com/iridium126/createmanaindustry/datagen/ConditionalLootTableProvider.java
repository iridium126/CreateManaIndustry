package com.iridium126.createmanaindustry.datagen;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.HolderLookup;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.neoforge.common.conditions.ConditionalOps;
import net.neoforged.neoforge.common.conditions.ICondition;
import net.neoforged.neoforge.common.conditions.WithConditions;

/**
 * Data provider for loot tables with top-level NeoForge conditions.
 * Definitions are attached to normal builders through
 * {@link CMIRegistrateExtensions#conditionalLoot} and participate in the same
 * data-generation lifecycle as Registrate's built-in providers.
 */
public final class ConditionalLootTableProvider implements DataProvider {
    static record Definition(ResourceLocation id,
            Function<HolderLookup.Provider, LootTable.Builder> factory,
            List<ICondition> conditions) {
    }

    private final PackOutput.PathProvider pathProvider;
    private final CompletableFuture<HolderLookup.Provider> lookupProvider;

    public ConditionalLootTableProvider(PackOutput output,
            CompletableFuture<HolderLookup.Provider> lookupProvider) {
        pathProvider = output.createRegistryElementsPathProvider(Registries.LOOT_TABLE);
        this.lookupProvider = lookupProvider;
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        return lookupProvider.thenCompose(provider -> {
            DynamicOps<JsonElement> ops = new ConditionalOps<>(
                    RegistryOps.create(JsonOps.INSTANCE, provider), ICondition.IContext.EMPTY);
            Codec<java.util.Optional<WithConditions<LootTable>>> conditionalCodec =
                    ConditionalOps.createConditionalCodecWithConditions(LootTable.DIRECT_CODEC);

            List<Definition> definitions = CMIRegistrateExtensions.conditionalLootDefinitions();
            List<CompletableFuture<?>> writes = new ArrayList<>(definitions.size());
            for (Definition definition : definitions) {
                LootTable table = definition.factory().apply(provider)
                        .setParamSet(net.minecraft.world.level.storage.loot.parameters.LootContextParamSets.BLOCK)
                        .setRandomSequence(ResourceLocation.fromNamespaceAndPath(
                                definition.id().getNamespace(), "blocks/" + definition.id().getPath()))
                        .build();
                JsonElement encoded;
                if (definition.conditions().isEmpty()) {
                    encoded = LootTable.DIRECT_CODEC.encodeStart(ops, table)
                            .getOrThrow(message -> new IllegalStateException(
                                    "Failed to encode loot table " + definition.id() + ": " + message));
                } else {
                    encoded = conditionalCodec.encodeStart(ops,
                                    java.util.Optional.of(new WithConditions<>(table,
                                            definition.conditions().toArray(ICondition[]::new))))
                            .getOrThrow(message -> new IllegalStateException(
                                    "Failed to encode conditional loot table " + definition.id() + ": " + message));
                }
                Path path = pathProvider.json(ResourceLocation.fromNamespaceAndPath(
                        definition.id().getNamespace(), "blocks/" + definition.id().getPath()));
                writes.add(DataProvider.saveStable(cache, encoded, path));
            }
            return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new));
        });
    }

    @Override
    public String getName() {
        return "Conditional Loot Tables";
    }
}
