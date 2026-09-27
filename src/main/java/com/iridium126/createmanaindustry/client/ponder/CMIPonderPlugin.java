package com.iridium126.createmanaindustry.client.ponder;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.simibubi.create.infrastructure.ponder.AllCreatePonderTags;
import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;
import net.createmod.ponder.api.scene.PonderStoryBoard;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;

/** Optional scenes use registry IDs; their classes are referenced only inside the dependency gate. */
public class CMIPonderPlugin implements PonderPlugin {
    static final ResourceLocation PROCESSING = id("processing"), MIST = id("mist"), MAGIC = id("magic_automation");
    private static ResourceLocation id(String path) { return CreateManaIndustry.modLoc(path); }
    private static ResourceLocation component(String path) {
        return path.contains(":") ? ResourceLocation.parse(path) : id(path);
    }
    private static boolean present(String path) {
        ResourceLocation key = component(path);
        return BuiltInRegistries.ITEM.containsKey(key) && BuiltInRegistries.ITEM.get(key) != Items.AIR;
    }
    static boolean available(String... paths) {
        for (String path : paths) if (!present(path)) return false;
        return true;
    }
    @Override public String getModId() { return CreateManaIndustry.MODID; }

    static void add(PonderSceneRegistrationHelper<ResourceLocation> helper, String name,
            PonderStoryBoard story, ResourceLocation tag, String... items) {
        for (String item : items)
            if (present(item)) helper.addStoryBoard(component(item), "workshop/" + name, story, tag);
    }

    @Override public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        add(helper, "allay_capture", AllayBurnerPonderScenes::capture, PROCESSING, "empty_allay_burner", "allay_burner");
        add(helper, "allay_fuel", AllayBurnerPonderScenes::solidFuel, PROCESSING, "allay_burner", "create:mechanical_arm");
        add(helper, "allay_liquid", AllayBurnerPonderScenes::liquidMedia, PROCESSING, "allay_burner", "liquid_media_bucket");
        add(helper, "allay_heating", AllayBurnerPonderScenes::heating, PROCESSING, "allay_burner", "liquid_media_bucket");
        add(helper, "allay_music", AllayBurnerPonderScenes::music, PROCESSING, "allay_burner");
        add(helper, "atomizer_setup", MistPonderScenes::atomizing, MIST, "kinetic_atomizer", "liquid_media_bucket", "liquid_mana_bucket");
        add(helper, "atomizer_range", MistPonderScenes::range, MIST, "kinetic_atomizer");
        add(helper, "soul_recovery", MistPonderScenes::soul, MIST, "allay_burner", "condenser", "liquid_soul_bucket");
        add(helper, "condenser_setup", MistPonderScenes::condenser, MIST, "condenser", "exposed_condenser", "weathered_condenser", "oxidized_condenser", "waxed_condenser", "waxed_exposed_condenser", "waxed_weathered_condenser", "waxed_oxidized_condenser", "coolant_bucket");
        add(helper, "mana_cogwheel", MistPonderScenes::cogwheel, MIST, "mana_cogwheel");
        add(helper, "mana_reversal", MistPonderScenes::reversal, MIST, "mana_cogwheel");
        add(helper, "amethyst_deposition", ProcessingPonderScenes::amethystDeposition, PROCESSING, "create:framed_glass_trapdoor", "amethyst_deposited_iron_sheet", "amethyst_deposited_copper_sheet", "amethyst_deposited_golden_sheet", "amethyst_deposited_brass_sheet");
        add(helper, "rose_deposition", ProcessingPonderScenes::roseDeposition, PROCESSING, "create:framed_glass_trapdoor", "rose_quartz_deposited_iron_sheet", "rose_quartz_deposited_copper_sheet", "rose_quartz_deposited_golden_sheet", "rose_quartz_deposited_brass_sheet");
        add(helper, "media_conversion", ProcessingPonderScenes::mediaConversion, PROCESSING, "liquid_media_bucket", "liquid_mana_bucket");
        add(helper, "mist_amethyst", ProcessingPonderScenes::glass, PROCESSING, "allay_burner", "kinetic_atomizer");
        add(helper, "prismarine_quartz", ProcessingPonderScenes::prismarine, PROCESSING, "prismarine_quartz", "prismarine_quartz_block", "molten_prismarine_quartz_bucket");
        add(helper, "rose_quartz", ProcessingPonderScenes::roseQuartz, PROCESSING, "molten_rose_quartz_bucket", "create:rose_quartz");
        add(helper, "coolant", ProcessingPonderScenes::coolant, PROCESSING, "coolant_bucket", "condenser");
        add(helper, "liquid_source", ProcessingPonderScenes::source, PROCESSING, "liquid_source_bucket");
        add(helper, "fuel_tank_storage", FuelTankPonderScenes::storage, PROCESSING, "molten_salt_fuel_tank");
        add(helper, "fuel_tank_windows", FuelTankPonderScenes::decoration, PROCESSING, "molten_salt_fuel_tank");
        add(helper, "fuel_tank_moving", FuelTankPonderScenes::moving, PROCESSING, "molten_salt_fuel_tank");
        add(helper, "fuel_rod_structure", FuelTankPonderScenes::structure, PROCESSING, "molten_salt_fuel_tank");
        if (ModList.get().isLoaded("trickster") && available("incomplete_emerald_knot", "trickster:emerald_knot", "trickster:cracked_emerald_knot")) {
            add(helper, "trickster_knots", TricksterPonderScenes::knots, MAGIC, "incomplete_emerald_knot", "incomplete_prismatic_knot", "incomplete_diamond_knot", "incomplete_echo_knot", "incomplete_astral_knot", "trickster:emerald_knot");
        }
        if (ModList.get().isLoaded("trickster") && available("kinetic_mana_generator", "trickster:charging_array", "trickster:emerald_knot")) {
            add(helper, "trickster_charging", TricksterPonderScenes::charging, MAGIC, "kinetic_mana_generator", "trickster:charging_array", "trickster:spell_construct", "trickster:modular_spell_construct");
            add(helper, "trickster_arm", TricksterPonderScenes::arm, MAGIC, "kinetic_mana_generator", "trickster:charging_array", "trickster:spell_construct", "trickster:modular_spell_construct", "create:mechanical_arm");
        }
        if (ModList.get().isLoaded("trickster") && available("trickster:spell_construct")) {
            add(helper, "trickster_display", TricksterPonderScenes::display, MAGIC, "trickster:spell_construct", "trickster:modular_spell_construct", "create:display_link");
        }
        if (ModList.get().isLoaded("trickster") && ModList.get().isLoaded("bits_n_bobs") && available("kinetics_spell_core", "trickster:modular_spell_construct")) {
            add(helper, "kinetics_core", ChainPonderScenes::core, MAGIC, "kinetics_spell_core", "trickster:modular_spell_construct");
        }
        if (ModList.get().isLoaded("hexcasting") && available("incomplete_cypher", "hexcasting:cypher", "hexcasting:focus")) {
            add(helper, "hex_spells", HexPonderScenes::spells, MAGIC, "incomplete_cypher", "incomplete_trinket", "incomplete_artifact", "hexcasting:cypher", "hexcasting:trinket", "hexcasting:artifact");
        }
        if (ModList.get().isLoaded("hexcasting") && available("incomplete_media_battery", "hexcasting:battery", "hexcasting:scroll")) {
            add(helper, "hex_battery", HexPonderScenes::battery, MAGIC, "incomplete_media_battery", "hexcasting:battery");
            add(helper, "hex_battery_transfer", HexPonderScenes::batteryTransfer, MAGIC, "incomplete_media_battery", "hexcasting:battery", "liquid_media_bucket");
        }
    }

    @Override public void registerTags(PonderTagRegistrationHelper<ResourceLocation> helper) {
        helper.registerTag(PROCESSING).addToIndex().item(BuiltInRegistries.ITEM.get(id("allay_burner")), true, false)
                .title("The Magic Workshop").description("Heat, coat and process materials with Create machinery").register();
        helper.registerTag(MIST).addToIndex().item(BuiltInRegistries.ITEM.get(id("kinetic_atomizer")), true, false)
                .title("Working with Mist").description("Make mist, collect it and use it to power machines").register();
        if (ModList.get().isLoaded("trickster") || ModList.get().isLoaded("hexcasting"))
            helper.registerTag(MAGIC).addToIndex().item(BuiltInRegistries.ITEM.get(id("liquid_media_bucket")), true, false)
                    .title("Magic Automation").description("Charge, assemble and connect magical items").register();
        tag(helper, AllCreatePonderTags.ARM_TARGETS, "allay_burner", "trickster:charging_array", "trickster:spell_construct", "trickster:modular_spell_construct");
        tag(helper, AllCreatePonderTags.DISPLAY_TARGETS, "trickster:spell_construct", "trickster:modular_spell_construct");
        tag(helper, PROCESSING, "empty_allay_burner", "allay_burner", "molten_salt_fuel_tank", "liquid_media_bucket", "liquid_mana_bucket", "liquid_source_bucket", "coolant_bucket", "prismarine_quartz", "prismarine_quartz_block", "molten_prismarine_quartz_bucket", "molten_rose_quartz_bucket", "create:rose_quartz", "create:framed_glass_trapdoor",
                "amethyst_deposited_iron_sheet", "amethyst_deposited_copper_sheet", "amethyst_deposited_golden_sheet", "amethyst_deposited_brass_sheet",
                "rose_quartz_deposited_iron_sheet", "rose_quartz_deposited_copper_sheet", "rose_quartz_deposited_golden_sheet", "rose_quartz_deposited_brass_sheet");
        tag(helper, MIST, "kinetic_atomizer", "mana_cogwheel", "allay_burner", "liquid_soul_bucket", "condenser", "exposed_condenser", "weathered_condenser", "oxidized_condenser", "waxed_condenser", "waxed_exposed_condenser", "waxed_weathered_condenser", "waxed_oxidized_condenser");
        if (ModList.get().isLoaded("trickster"))
            tag(helper, MAGIC, "kinetic_mana_generator", "incomplete_emerald_knot", "incomplete_prismatic_knot", "incomplete_diamond_knot", "incomplete_echo_knot", "incomplete_astral_knot", "trickster:emerald_knot", "trickster:charging_array", "trickster:spell_construct", "trickster:modular_spell_construct");
        if (ModList.get().isLoaded("trickster") && ModList.get().isLoaded("bits_n_bobs"))
            tag(helper, MAGIC, "kinetics_spell_core");
        if (ModList.get().isLoaded("hexcasting"))
            tag(helper, MAGIC, "incomplete_cypher", "incomplete_trinket", "incomplete_artifact", "incomplete_media_battery", "hexcasting:cypher", "hexcasting:trinket", "hexcasting:artifact", "hexcasting:battery");
        tag(helper, AllCreatePonderTags.KINETIC_SOURCES, "mana_cogwheel");
        tag(helper, AllCreatePonderTags.KINETIC_APPLIANCES, "kinetic_atomizer", "kinetic_mana_generator");
        tag(helper, AllCreatePonderTags.FLUIDS, "condenser", "molten_salt_fuel_tank", "kinetic_atomizer");
        tag(helper, AllCreatePonderTags.CONTRAPTION_ACTOR, "molten_salt_fuel_tank");
    }
    private static void tag(PonderTagRegistrationHelper<ResourceLocation> helper, ResourceLocation tag, String... items) {
        for (String item : items) if (present(item)) helper.addToTag(tag).add(component(item));
    }
}
