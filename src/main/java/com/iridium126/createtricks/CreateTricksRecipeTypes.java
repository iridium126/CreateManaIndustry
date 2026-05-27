package com.iridium126.createtricks;

import java.util.function.Supplier;

import com.iridium126.createtricks.content.recipes.SpellInkRecipe;
import com.simibubi.create.content.processing.recipe.StandardProcessingRecipe;
import com.simibubi.create.foundation.recipe.IRecipeTypeInfo;

import net.createmod.catnip.lang.Lang;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public enum CreateTricksRecipeTypes implements IRecipeTypeInfo {
	SPELL_INK_EMPTYING(SpellInkRecipe::new);

	private final ResourceLocation id;
	private final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<?>> serializerObject;
	private final DeferredHolder<RecipeType<?>, RecipeType<?>> typeObject;
	private final Supplier<RecipeType<?>> type;

	CreateTricksRecipeTypes(Supplier<RecipeSerializer<?>> serializerSupplier, Supplier<RecipeType<?>> typeSupplier) {
		String name = Lang.asId(name());
		id = CreateTricks.modLoc(name);
		serializerObject = Registers.SERIALIZER.register(name, serializerSupplier);
		typeObject = Registers.TYPE.register(name, typeSupplier);
		type = typeObject;
	}

	CreateTricksRecipeTypes(StandardProcessingRecipe.Factory<?> factory) {
		this(() -> new StandardProcessingRecipe.Serializer<>(factory), () -> RecipeType.simple(ResourceLocation.fromNamespaceAndPath("create", "emptying")));
	}

	public static void register(IEventBus modEventBus) {
		Registers.SERIALIZER.register(modEventBus);
		Registers.TYPE.register(modEventBus);
	}

	@Override
	public ResourceLocation getId() {
		return id;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends RecipeSerializer<?>> T getSerializer() {
		return (T) serializerObject.get();
	}

	@SuppressWarnings("unchecked")
	@Override
	public <I extends RecipeInput, R extends Recipe<I>> RecipeType<R> getType() {
		return (RecipeType<R>) type.get();
	}

	private static class Registers {
		private static final DeferredRegister<RecipeSerializer<?>> SERIALIZER = DeferredRegister.create(BuiltInRegistries.RECIPE_SERIALIZER, CreateTricks.MODID);
		private static final DeferredRegister<RecipeType<?>> TYPE = DeferredRegister.create(Registries.RECIPE_TYPE, CreateTricks.MODID);
	}
}
