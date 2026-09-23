package com.iridium126.createmanaindustry.compat.ysm.model;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;

/** Strict resource JSON reader with limits applied before allocating nested trees. */
public final class YsmJson {
    public static JsonObject object(String text) {
        return object(text, YsmGeometry.MAX_JSON);
    }
    public static JsonObject resource(String text) { return object(text, 16 * 1024 * 1024); }
    private static JsonObject object(String text, int maxCharacters) {
        if (text.length() > maxCharacters) throw new IllegalArgumentException("Geometry JSON is too large");
        try (var reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            JsonElement result = value(reader, 0, new int[]{0});
            if (!result.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT)
                throw new IllegalArgumentException("Expected one JSON object");
            return result.getAsJsonObject();
        } catch (IOException | NumberFormatException failure) { throw new IllegalArgumentException("Invalid resource JSON", failure); }
    }
    private static JsonElement value(JsonReader reader, int depth, int[] nodes) throws IOException {
        if (depth > 64 || ++nodes[0] > 200_000) throw new IllegalArgumentException("Resource JSON is too complex");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                var result = new JsonObject(); reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (result.has(key)) throw new IllegalArgumentException("Duplicate JSON key");
                    result.add(key, value(reader, depth + 1, nodes));
                }
                reader.endObject(); yield result;
            }
            case BEGIN_ARRAY -> {
                var result = new JsonArray(); reader.beginArray();
                while (reader.hasNext()) result.add(value(reader, depth + 1, nodes));
                reader.endArray(); yield result;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> {
                String number = reader.nextString();
                if (number.length() > 128) throw new IllegalArgumentException("Resource number is too long");
                yield new JsonPrimitive(new BigDecimal(number));
            }
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new IllegalArgumentException("Unexpected JSON token");
        };
    }
    private YsmJson() {}
}
