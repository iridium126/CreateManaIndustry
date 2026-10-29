package com.iridium126.createmanaindustry.client.particles.engine;
import com.iridium126.createmanaindustry.client.particles.interaction.CombatSpecs;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

/**
 * Self-hosted sprite atlas for textured (ALPHA/OPAQUE) particles, assembled on
 * the render thread from the mod's own asset PNGs. The SPRITE atlas packs the
 * vanilla {@code cherry_0..11} frames (copied into mod assets) for the ALPHA
 * path plus the combat frames — vanilla {@code critical_hit}, {@code
 * enchanted_hit}, {@code damage} and {@code generic_0..7} (poof order 7→0) as
 * {@code combat_*} — for the OPAQUE combat emitters ({@code CombatSpecs});
 * 23 native-resolution rectangles in 32x27 pixels. The atlas id is owned here;
 * rectangles and shader frame indices share {@link ParticleAtlasLayout}.
 */
public final class ParticleAtlas {

    private static final String[] SPRITE_FILES = {
            "cherry_0", "cherry_1", "cherry_2", "cherry_3", "cherry_4", "cherry_5",
            "cherry_6", "cherry_7", "cherry_8", "cherry_9", "cherry_10", "cherry_11",
            // combat frames: frameBase 12..22 (see CombatSpecs); poof keeps the
            // vanilla poof.json order generic_7 → generic_0
            "combat_crit", "combat_magic", "combat_heart",
            "combat_poof_0", "combat_poof_1", "combat_poof_2", "combat_poof_3",
            "combat_poof_4", "combat_poof_5", "combat_poof_6", "combat_poof_7"
    };

    /** Shared ALPHA-sprite + OPAQUE-combat atlas (see class doc). */
    public static final ParticleAtlas SPRITE = new ParticleAtlas(SPRITE_FILES, false);
    /**
     * MODEL atlas: frame 0 is the vanilla allay entity texture, frames 1..6
     * the held-item sword tiers ({@code EmitterSpec.HeldItem} order) — ONE
     * texture so the shader-pack merged path can sample all three kinds.
     * Native 32x32 allay, six 16x16 swords and twelve 3x3 recolored feathers
     * occupy 64x48 pixels. Mip levels stop at 4, before 16px material regions mix.
     */
    public static final ParticleAtlas ALLAY = new ParticleAtlas(new String[] {
            "allay_0",
            "sword_wooden", "sword_stone", "sword_golden", "sword_iron",
            "sword_diamond", "sword_netherite" },
            true);

    private final String name;
    private final String[] files;
    private final int frames;
    /** Whether to generate mipmaps and minify through them on this atlas. */
    private final boolean mipmap;
    private int textureId = -1;
    private int width,height;
    public int width(){return width;}
    public int height(){return height;}

    private ParticleAtlas(String[] files, boolean mipmap) {
        this.files = files.clone();
        this.name = files[0].split("_")[0];
        this.frames = files.length;
        this.mipmap = mipmap;
    }

    public int frames() {
        return frames;
    }

    /** Whether the atlas is loaded (texture id valid). */
    public boolean ready() {
        return textureId >= 0;
    }

    public int textureId() {
        return textureId;
    }

    /**
     * Decodes {@code frames} PNGs from the mod's assets and uploads them as a
     * compact native-resolution atlas. Lazy on the render thread; no-op if loaded.
     */
    public void ensureLoaded() {
        if (textureId >= 0)
            return;
        try {
            Minecraft mc = Minecraft.getInstance();
            var rm = mc.getResourceManager();
            if (rm == null)
                return;

            NativeImage[] imgs = new NativeImage[frames];
            for (int i = 0; i < frames; i++) {
                ResourceLocation id = CreateManaIndustry.modLoc("textures/particle/" + files[i] + ".png");
                try (InputStream in = rm.open(id)) {
                    imgs[i] = NativeImage.read(in);
                } catch (Exception e) {
                    CreateManaIndustry.LOGGER.warn("[CMI particles] cannot read sprite {}", id, e);
                    for (int j = 0; j <= i; j++)
                        if (imgs[j] != null)
                            imgs[j].close();
                    return;
                }
            }

            int texW = this==ALLAY?ParticleAtlasLayout.ALLAY_WIDTH:ParticleAtlasLayout.SPRITE_WIDTH;
            int texH = this==ALLAY?ParticleAtlasLayout.ALLAY_HEIGHT:ParticleAtlasLayout.SPRITE_HEIGHT;
            NativeImage canvas = new NativeImage(texW, texH, true);
            try {
                for (int i = 0; i < frames; i++) {
                    ParticleAtlasLayout.copy(canvas,imgs[i],this==ALLAY?ParticleAtlasLayout.model(i):ParticleAtlasLayout.sprite(i));
                }

                if (this == ALLAY) AllayFeatherAppearance.bake(canvas, rm);
                int[] rgba = canvas.getPixelsRGBA();
                int texelCount = rgba.length;
                ByteBuffer pixels = ByteBuffer.allocateDirect(texelCount * 4)
                        .order(ByteOrder.nativeOrder());
                for (int v : rgba)
                    pixels.putInt(v);
                pixels.flip();

                textureId = GL11.glGenTextures();
                width=texW;height=texH;
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                ParticleGLUtil.prepareClientUpload(); // guard PBO / unpack-state leftovers
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, texW, texH, 0,
                        GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
                if (this.mipmap) {
                    // Stop before the aligned 16x16 sword/body/feather regions mix.
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL12.GL_TEXTURE_MAX_LEVEL,ParticleAtlasLayout.ALLAY_MAX_MIP);
                    GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
                            GL30.GL_NEAREST_MIPMAP_LINEAR);
                } else {
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                }

                CreateManaIndustry.LOGGER.info("[CMI particles] sprite atlas {} ready: {}x{}",
                        name, texW, texH);
            } finally {
                // close on EVERY path — a failure mid-compose/mid-upload must
                // not leak the canvas or the frame images (the old code only
                // closed them on the success path)
                canvas.close();
                for (NativeImage img : imgs)
                    img.close();
            }
        } catch (RuntimeException | LinkageError e) {
            // A failure after glGenTextures leaves a half-built texture bound to
            // textureId: recycle it and reset the id so later frames retry the
            // upload instead of treating a storage-less texture as loaded.
            free();
            CreateManaIndustry.LOGGER.error("[CMI particles] failed to build sprite atlas " + name, e);
        }
    }

    /** Binds the atlas on the given texture unit (shader samples it there). */
    public void bind(int unit) {
        if (textureId < 0)
            return;
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
    }

    public void free() {
        if (textureId >= 0) {
            com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames.deleteTexture(textureId);
            textureId = -1;
            width=height=0;
        }
    }
}
