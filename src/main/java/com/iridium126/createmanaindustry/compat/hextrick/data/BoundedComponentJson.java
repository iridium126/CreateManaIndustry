package com.iridium126.createmanaindustry.compat.hextrick.data;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;

/** Check the native entity-name string before Minecraft's recursive component codec. */
final class BoundedComponentJson {
    private BoundedComponentJson() {}

    static void validate(String value, DecodeBudget budget) {
        int open = 0;
        try (var reader = new JsonReader(new StringReader(value))) {
            while (reader.peek() != JsonToken.END_DOCUMENT) {
                switch (reader.peek()) {
                    case BEGIN_ARRAY -> { budget.push(); open++; reader.beginArray(); }
                    case BEGIN_OBJECT -> { budget.push(); open++; reader.beginObject(); }
                    case END_ARRAY -> { reader.endArray(); budget.pop(); open--; }
                    case END_OBJECT -> { reader.endObject(); budget.pop(); open--; }
                    case NAME -> { budget.push(); try { reader.nextName(); } finally { budget.pop(); } }
                    case STRING, NUMBER -> { budget.push(); try { reader.nextString(); } finally { budget.pop(); } }
                    case BOOLEAN -> { budget.push(); try { reader.nextBoolean(); } finally { budget.pop(); } }
                    case NULL -> { budget.push(); try { reader.nextNull(); } finally { budget.pop(); } }
                    default -> throw new DecodeBudget.DecodeFailure("component_json");
                }
            }
            if (open != 0) throw new DecodeBudget.DecodeFailure("component_json");
        } catch (IOException | IllegalStateException failure) {
            throw new DecodeBudget.DecodeFailure("component_json", failure);
        } finally {
            while (open-- > 0) budget.pop();
        }
    }
}
