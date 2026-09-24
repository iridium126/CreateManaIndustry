package com.iridium126.createmanaindustry.client.render;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.compat.hexcasting.ysm.InlineYsmGeometryData;
import com.iridium126.createmanaindustry.compat.ysm.render.YsmGeometryThumbnail;
import com.iridium126.createmanaindustry.compat.ysm.net.ClientboundYsmPreviewPacket;
import com.iridium126.createmanaindustry.compat.ysm.net.ServerboundYsmPreviewRequestPacket;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import com.mojang.blaze3d.platform.NativeImage;

/** Session cache and request coalescer for server-rendered YSM thumbnails. */
@EventBusSubscriber(modid = CreateManaIndustry.MODID, value = Dist.CLIENT)
public final class YsmClientPreviews {
    static final class Entry {
        ResourceLocation texture;
        String error;
        final YsmPreviewRequestTimer request = new YsmPreviewRequestTimer();
    }

    private static final int MAX_CACHE = 1024;
    private static final LinkedHashMap<String, Entry> CACHE = new LinkedHashMap<>(128, .75f, true);

    static Entry get(InlineYsmGeometryData data) {
        String cacheKey = data.cacheKey();
        Entry entry = entry(cacheKey);
        request(data, entry);
        return entry;
    }

    private static void request(InlineYsmGeometryData data, Entry entry) {
        if (entry.texture != null || entry.error != null) return;
        long now = System.currentTimeMillis();
        if (!entry.request.shouldRequest(now)) return;
        if (Minecraft.getInstance().getConnection() == null) return;
        entry.request.requested(now);
        PacketDistributor.sendToServer(new ServerboundYsmPreviewRequestPacket(data.key(), data.isCube()));
    }

    public static void accept(ClientboundYsmPreviewPacket packet) {
        String cacheKey = "ref:" + (packet.cube() ? "c:" : "g:") + packet.key();
        Entry entry = entry(cacheKey);
        entry.request.received();
        if (packet.kind() == ClientboundYsmPreviewPacket.ERROR) {
            if (packet.retryable()) {
                entry.error = null;
                entry.request.retryAfter(System.currentTimeMillis(), 2000);
            } else entry.error = packet.reason();
            return;
        }
        NativeImage image = null;
        try {
            image = NativeImage.read(new ByteArrayInputStream(packet.png()));
            if (image.getWidth() != YsmGeometryThumbnail.SIZE || image.getHeight() != YsmGeometryThumbnail.SIZE)
                throw new IOException("Unexpected YSM preview dimensions");
            ResourceLocation id = CreateManaIndustry.modLoc("ysm_preview/" + cacheSlug(cacheKey));
            if (entry.texture != null) Minecraft.getInstance().getTextureManager().release(entry.texture);
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(image));
            image = null; // DynamicTexture owns the native image after registration.
            entry.texture = id;
            entry.error = null;
        } catch (IOException | RuntimeException failure) {
            entry.error = failure.getMessage() == null ? "Invalid YSM preview PNG" : failure.getMessage();
        } finally { if (image != null) image.close(); }
    }

    private static Entry entry(String key) {
        Entry result = CACHE.get(key);
        if (result == null) {
            result = new Entry();
            CACHE.put(key, result);
            while (CACHE.size() > MAX_CACHE) {
                var first = CACHE.entrySet().iterator().next();
                release(first.getValue());
                CACHE.remove(first.getKey());
            }
        }
        return result;
    }

    private static void release(Entry entry) {
        if (entry.texture != null) Minecraft.getInstance().getTextureManager().release(entry.texture);
        entry.texture = null;
    }
    private static String cacheSlug(String key) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 32);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void clear() {
        for (Entry entry : CACHE.values()) release(entry);
        CACHE.clear();
    }
    @SubscribeEvent public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }

    private YsmClientPreviews() {}
}
