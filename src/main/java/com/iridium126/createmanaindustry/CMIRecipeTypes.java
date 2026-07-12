package com.iridium126.createmanaindustry;

import java.util.function.Supplier;

import com.iridium126.createmanaindustry.content.recipes.HeatedCompactingRecipe;
import com.simibubi.create.content.processing.recipe.StandardProcessingRecipe;
import com.simibubi.create.foundation.recipe.IRecipeTypeInfo;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class CMIRecipeTypes {

    private static final DeferredRegister<RecipeSerializer<?>> SERIALIZER_REGISTER =
            DeferredRegister.create(BuiltInRegistries.RECIPE_SERIALIZER, CreateManaIndustry.MODID);
    private static final DeferredRegister<RecipeType<?>> TYPE_REGISTER =
            DeferredRegister.create(Registries.RECIPE_TYPE, CreateManaIndustry.MODID);

    public static final ResourceLocation HEATED_COMPACTING_ID =
            CreateManaIndustry.modLoc("heated_compacting");

    private static final Supplier<RecipeSerializer<?>> HEATED_COMPACTING_SERIALIZER =
            SERIALIZER_REGISTER.register("heated_compacting",
                    () -> new StandardProcessingRecipe.Serializer<>(HeatedCompactingRecipe::new));

    private static final Supplier<RecipeType<?>> HEATED_COMPACTING_TYPE =
            TYPE_REGISTER.register("heated_compacting",
                    () -> RecipeType.simple(HEATED_COMPACTING_ID));

    public static final IRecipeTypeInfo HEATED_COMPACTING = new IRecipeTypeInfo() {
        @Override
        public ResourceLocation getId() {
            return HEATED_COMPACTING_ID;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T extends RecipeSerializer<?>> T getSerializer() {
            return (T) HEATED_COMPACTING_SERIALIZER.get();
        }

        @SuppressWarnings("unchecked")
        @Override
        public <I extends RecipeInput, R extends Recipe<I>> RecipeType<R> getType() {
            return (RecipeType<R>) HEATED_COMPACTING_TYPE.get();
        }
    };

    private CMIRecipeTypes() {}

    public static void register(IEventBus modEventBus) {
        SERIALIZER_REGISTER.register(modEventBus);
        TYPE_REGISTER.register(modEventBus);
    }
}
