package com.iridium126.createmanaindustry.compat.hexcasting.ysm;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.samsthenerd.inline.api.InlineData;
import java.util.Objects;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Inline payload carrying only a compact persistent geometry reference. */
public final class InlineYsmGeometryData implements InlineData<InlineYsmGeometryData> {
    public enum Kind { GROUP, CUBE }
    public static final ResourceLocation RENDERER_ID = CreateManaIndustry.modLoc("ysm_geometry");
    public static final InlineYsmGeometryDataType TYPE = new InlineYsmGeometryDataType();

    private final Kind kind;
    private final String key;
    private final String cacheKey;

    private InlineYsmGeometryData(Kind kind, String key) {
        this.kind = Objects.requireNonNull(kind);
        this.key = checkedKey(key);
        this.cacheKey = "ref:" + (kind == Kind.CUBE ? "c:" : "g:") + key;
    }

    public static InlineYsmGeometryData group(String key) { return new InlineYsmGeometryData(Kind.GROUP, key); }
    public static InlineYsmGeometryData cube(String key) { return new InlineYsmGeometryData(Kind.CUBE, key); }

    public Kind kind() { return kind; }
    public String key() { return key; }
    public boolean isCube() { return kind == Kind.CUBE; }
    public String cacheKey() { return cacheKey; }

    @Override public InlineYsmGeometryDataType getType() { return TYPE; }
    @Override public ResourceLocation getRendererId() { return RENDERER_ID; }
    @Override public Component asText(boolean withExtra) { return Component.literal("◈").withStyle(asStyle(withExtra)); }

    private String encode() {
        return "1:" + (kind == Kind.CUBE ? "c:" : "g:") + key;
    }

    private static InlineYsmGeometryData decode(String value) {
        String[] parts = value.split(":", 3);
        if (parts.length != 3 || !parts[0].equals("1")) throw new IllegalArgumentException("Unknown YSM inline preview version");
        return switch (parts[1]) {
            case "g" -> group(parts[2]);
            case "c" -> cube(parts[2]);
            default -> throw new IllegalArgumentException("Unknown YSM inline preview kind");
        };
    }

    private static String checkedKey(String key) {
        if (key == null || !key.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid YSM reference key");
        return key;
    }
    public static final class InlineYsmGeometryDataType implements InlineDataType<InlineYsmGeometryData> {
        private static final Codec<InlineYsmGeometryData> CODEC = Codec.STRING.flatXmap(encoded -> {
            try { return DataResult.success(decode(encoded)); }
            catch (IllegalArgumentException failure) { return DataResult.error(() -> "Invalid YSM inline preview: " + failure.getMessage()); }
        }, value -> {
            try { return DataResult.success(value.encode()); }
            catch (IllegalArgumentException failure) { return DataResult.error(() -> "Invalid YSM inline preview: " + failure.getMessage()); }
        });
        @Override public ResourceLocation getId() { return RENDERER_ID; }
        @Override public Codec<InlineYsmGeometryData> getCodec() { return CODEC; }
    }
}
