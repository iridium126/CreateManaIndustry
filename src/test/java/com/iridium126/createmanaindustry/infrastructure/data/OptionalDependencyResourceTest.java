package com.iridium126.createmanaindustry.infrastructure.data;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * Data-pack resources that reference optional mods must carry a
 * {@code neoforge:conditions} gate. Without it, RecipeManager, LootDataType and
 * TagLoader report parse errors whenever the dependency is not installed.
 */
class OptionalDependencyResourceTest {

    private static final String RECIPE_DIR = "/data/createmanaindustry/recipe";
    private static final String CONDITIONAL_BLOCK = "createmanaindustry:kinetic_mana_generator";

    /** CMI items registered only from {@code CMIItems#register}/{@code CMIBlocks#register} under Trickster. */
    private static final Set<String> TRICKSTER_GATED_ITEMS = Set.of(
            "createmanaindustry:incomplete_astral_knot",
            "createmanaindustry:incomplete_diamond_knot",
            "createmanaindustry:incomplete_echo_knot",
            "createmanaindustry:incomplete_emerald_knot",
            "createmanaindustry:incomplete_prismatic_knot",
            "createmanaindustry:kinetics_spell_core",
            CONDITIONAL_BLOCK);

    @Test
    void optionalModRecipesDeclareModLoadedCondition() throws Exception {
        List<Path> recipes = jsonFiles(RECIPE_DIR);
        assertFalse(recipes.isEmpty(), RECIPE_DIR);
        int guarded = 0;
        for (Path recipe : recipes) {
            JsonObject json = read(recipe);
            Set<String> mods = requiredMods(json);
            if (mods.isEmpty()) continue;
            guarded++;
            for (String mod : mods) assertModLoaded(json, recipe.getFileName().toString(), mod);
        }
        assertTrue(guarded > 0, "no optional-mod recipe was checked");
    }

    @Test
    void tricksterGatedLootTableAndAdvancementsAreConditional() throws IOException {
        for (String name : List.of(
                "/data/createmanaindustry/loot_table/blocks/kinetic_mana_generator.json",
                "/data/createmanaindustry/advancement/recipes/misc/kinetics_spell_core.json",
                "/data/createmanaindustry/advancement/recipes/misc/kinetic_mana_generator.json")) {
            assertModLoaded(readResource(name), name, "trickster");
        }
    }

    @Test
    void conditionalBlockHasOptionalTagEntry() throws IOException {
        JsonArray values = readResource("/data/minecraft/tags/block/mineable/pickaxe.json")
                .getAsJsonArray("values");
        assertNotNull(values);
        boolean optional = false;
        for (JsonElement value : values) {
            if (value.isJsonPrimitive()) {
                assertNotEquals(CONDITIONAL_BLOCK, value.getAsString(),
                        "conditional block must not be a required tag entry");
                continue;
            }
            JsonObject entry = value.getAsJsonObject();
            if (CONDITIONAL_BLOCK.equals(string(entry, "id"))) {
                assertNotNull(entry.get("required"), "tag entry must declare required");
                assertFalse(entry.get("required").getAsBoolean());
                optional = true;
            }
        }
        assertTrue(optional, "missing optional tag entry for " + CONDITIONAL_BLOCK);
    }

    @Test
    void patchouliOptionalEntryUsesAlwaysValidIcon() throws IOException {
        String path = "/assets/hexcasting/patchouli_books/thehexbook/"
                + "en_us/entries/createmanaindustry/trickster_patterns.json";
        String icon = string(readResource(path), "icon");
        assertNotNull(icon);
        assertFalse(icon.startsWith("trickster:"),
                "Patchouli parses icons even when the entry flag hides it");
    }

    /** Optional namespaces whose absence is exercised by the reference client profile. */
    private static Set<String> requiredMods(JsonElement element) {
        Set<String> mods = new TreeSet<>();
        collectStrings(element, value -> {
            if (value.startsWith("trickster:")) mods.add("trickster");
            if (value.startsWith("ars_nouveau:")) mods.add("ars_nouveau");
            if (TRICKSTER_GATED_ITEMS.contains(value)) mods.add("trickster");
        });
        return mods;
    }

    private static void collectStrings(JsonElement element, java.util.function.Consumer<String> sink) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            sink.accept(element.getAsString());
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) collectStrings(child, sink);
        } else if (element.isJsonObject()) {
            for (var entry : element.getAsJsonObject().entrySet()) collectStrings(entry.getValue(), sink);
        }
    }

    private static void assertModLoaded(JsonObject json, String source, String mod) {
        JsonArray conditions = json.getAsJsonArray("neoforge:conditions");
        assertNotNull(conditions, source + ": missing neoforge:conditions for " + mod);
        boolean declared = false;
        for (JsonElement condition : conditions) {
            if (!condition.isJsonObject()) continue;
            JsonObject object = condition.getAsJsonObject();
            if ("neoforge:mod_loaded".equals(string(object, "type"))
                    && mod.equals(string(object, "modid"))) {
                declared = true;
                break;
            }
        }
        assertTrue(declared, source + ": missing neoforge:mod_loaded condition for " + mod);
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static JsonObject read(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static JsonObject readResource(String name) throws IOException {
        try (var stream = OptionalDependencyResourceTest.class.getResourceAsStream(name)) {
            assertNotNull(stream, name);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    private static List<Path> jsonFiles(String resourceDir) throws IOException, URISyntaxException {
        URL url = OptionalDependencyResourceTest.class.getResource(resourceDir);
        assertNotNull(url, resourceDir);
        assertEquals("file", url.getProtocol(), resourceDir + " must be a directory on the test classpath");
        try (Stream<Path> files = Files.walk(Path.of(url.toURI()))) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
        }
    }
}
