package com.iridium126.createmanaindustry.client.ponder;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Verify resource formatting and instruction boundaries without starting a Minecraft client. */
class PonderSceneContractTest {
    @Test
    void allBilingualCaptionsAcceptEmptyFormatArguments() throws IOException {
        for (String language : List.of("en_us", "zh_cn")) {
            var entries = language(language).entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith("createmanaindustry.ponder."))
                    .filter(entry -> entry.getKey().matches(".*\\.text_\\d+"))
                    .toList();
            assertFalse(entries.isEmpty(), language);
            for (var entry : entries)
                assertDoesNotThrow(() -> String.format(Locale.ROOT, entry.getValue().getAsString()),
                        language + ": " + entry.getKey());
        }
    }

    @Test
    void waterMistThresholdRendersOneLiteralPercentInBothLanguages() throws IOException {
        String key = "createmanaindustry.ponder.water_mist_irrigation.text_2";
        assertEquals("The water mist concentration at the farmland must be greater than 10%",
                String.format(Locale.ROOT, language("en_us").get(key).getAsString()));
        assertEquals("耕地所在位置的水雾浓度需要大于 10%",
                String.format(Locale.ROOT, language("zh_cn").get(key).getAsString()));
    }

    @Test
    void captionsUseTheFormattingOverloadForPonderEditingMode() throws IOException {
        var calls = calls("Workshop", "explain");
        assertTrue(calls.stream().anyMatch(call -> call.name.equals("text")
                && call.desc.startsWith("(Ljava/lang/String;[Ljava/lang/Object;)")));
        assertFalse(calls.stream().anyMatch(call -> call.name.equals("text")
                && call.desc.startsWith("(Ljava/lang/String;)")));
    }

    @Test
    void replacingVisibleBlocksDoesNotRevealAnotherAnimatedSection() throws IOException {
        var replacement = calls("Workshop", "replace");
        assertTrue(replacement.stream().anyMatch(call -> call.name.equals("setBlock")));
        assertTrue(replacement.stream().anyMatch(call -> call.name.equals("modifyBlockEntity")));
        assertFalse(replacement.stream().anyMatch(call -> call.name.equals("showSection")
                || call.name.equals("place") || call.name.equals("hideSection")));
        assertTrue(calls("Workshop", "place").stream().anyMatch(call -> call.name.equals("showSection")));
    }

    @Test
    void openingAndClosingExistingLidsOnlyChangesTheirState() throws IOException {
        var calls = calls("ProcessingPonderScenes", "setLidOpen");
        assertTrue(calls.stream().anyMatch(call -> call.name.equals("modifyBlock")));
        assertFalse(calls.stream().anyMatch(call -> call.name.equals("place")
                || call.name.equals("showSection")));
    }

    private static JsonObject language(String name) throws IOException {
        String resource = "/assets/createmanaindustry/lang/" + name + ".json";
        try (var stream = PonderSceneContractTest.class.getResourceAsStream(resource)) {
            assertNotNull(stream, resource);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static List<MethodInsnNode> calls(String className, String methodName) throws IOException {
        String resource = "/com/iridium126/createmanaindustry/client/ponder/" + className + ".class";
        ClassNode type = new ClassNode();
        try (var stream = PonderSceneContractTest.class.getResourceAsStream(resource)) {
            assertNotNull(stream, resource);
            new ClassReader(stream).accept(type, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        var result = new ArrayList<MethodInsnNode>();
        boolean found = false;
        for (var method : type.methods) {
            if (!method.name.equals(methodName)) continue;
            found = true;
            for (var instruction : method.instructions)
                if (instruction instanceof MethodInsnNode call) result.add(call);
        }
        assertTrue(found, className + "." + methodName);
        return result;
    }
}
