package com.iridium126.createmanaindustry.client.particles.emitter;

import java.util.Arrays;
import java.util.Objects;

import net.minecraft.world.phys.Vec3;

/**
 * Immutable, data-driven description of one particle emitter behaviour.
 * <p>
 * Instances are packed into a 20-vec4 (320 byte) GPU header block per distinct
 * spec (used by the compute update/emit/keygen passes and the render vertex
 * shaders), so specs with identical fields collapse to one header. Origin is
 * deliberately NOT part of the spec — the spawn position is supplied per
 * call/command.
 * <p>
 * Header layout (index 0..15 mirrors the original 16-vec4 layout; the new
 * material/collide/flutter/spin flags ride on the once-reserved vec4 #7 and a
 * fresh 16..19 block holds the collision bake index and sprite count):
 * <pre>
 *   0:  (internalTypeId,0,0)           size
 *   1:  shape, speedMin, speedMax,      radius
 *   2:  gravity.xyz                     drag
 *   3:  acceleration.xyz                windStrength
 *   4:  windDirection.xyz               rotation
 *   5:  lifeMin, lifeMax, sizeStart,    sizeEnd
 *   6:  sizeEase, coneTanHalf,          colorCount, glow
 *   7:  material, collideMode,          flutter, spin
 *   8..15: colour keyframes RGBA (padded with the last colour)
 *  16:  reserved 0 (collision bake slices are selected per particle on the
 *      GPU — the header deliberately carries no world-position state),
 *      spriteCount, 0, 0
 *  17:  animation (0 FLY..3 DEATH, MODEL only), shader colour mode (additive),
 *      spawnStyle (storm members write 3 post-pack), .z is MATERIALLY SPLIT —
 *      MODEL reads it as heldItem while textured (ALPHA/OPAQUE) reads it as
 *      lightMode (1 = p2.w carries the spawn-time packed light), 0
 *  18:   reserved (storm parameters)
 *  19:   plane normal xyz for {@link EmitterShape#PLANE}, zero otherwise
 * </pre>
 */
public final class EmitterSpec {

    /** Procedural pose set for {@link ParticleTypes.Material#MODEL} emitters (header 17.x). */
    public enum Animation {
        /** Vanilla hover: wing flap, bobbing, arm sway (limbSwingAmount from speed). */
        FLY(0),
        /**
         * Full vanilla jukebox dance: body/head roll plus the periodic burst
         * spin -- {@code isDancing()} always includes the {@code isSpinning()}
         * rhythm in vanilla, so there is no separate spin-only pose.
         */
        DANCE(1),
        /**
         * Arms raised as if carrying an item. With a {@link HeldItem} on the
         * spec the item itself renders at the vanilla hand anchor (vanilla
         * raising ramp: {@code Allay.getHoldingItemAnimationProgress}); with
         * no item the pose is unchanged from before.
         */
        HOLD(2),
        /**
         * Vanilla death sequence: the idle pose keeps playing while the corpse
         * rolls up to 90 degrees about the world Z axis with vanilla's sqrt
         * easing over exactly 20 ticks ({@code LivingEntityRenderer
         * .setupRotations}); pair with a ~1 s lifetime and gravity.
         */
        DEATH(3);

        final int index;

        Animation(int index) {
            this.index = index;
        }

        public int index() {
            return index;
        }

        public static Animation byIndex(int i) {
            return i == 1 ? DANCE : (i == 2 ? HOLD : (i == 3 ? DEATH : FLY));
        }
    }

    /** How an alpha particle interacts with the collision volume. */
    public enum CollideMode {
        /** No collision (additive / atmospheric particles). */
        NONE(0),
        /** Settle on surfaces: stop sliding along the contacted axis, rest on tops. */
        REST(1),
        /** Vanilla cherry-leaf behaviour: touching the ground removes the particle. */
        DIE_ON_GROUND(2);

        final int index;

        CollideMode(int index) {
            this.index = index;
        }

        public int index() {
            return index;
        }

        public static CollideMode byIndex(int i) {
            return switch (i) {
                case 1 -> REST;
                case 2 -> DIE_ON_GROUND;
                default -> NONE;
            };
        }
    }

    /**
     * Held-item ids carried in header vec4 #17.z. The id space is shared with
     * the server's dive-wave sword-tier byte ({@code AllayStormManager.
     * swordTier}) and indexes the sword frames of the MODEL atlas (frame 0 is
     * the allay body, frames 1..6 the tiers below in order) — one enum, three
     * consumers, no mapping layer. The geometry is a static bake of the
     * vanilla flat {@code item/handheld} model copied into mod assets (pack
     * independent); all six tiers share one silhouette, so the shader selects
     * the frame through a per-tier UV remap.
     */
    public enum HeldItem {
        NONE(0),
        /** Frames the vanilla wooden_sword sprite. */
        WOODEN_SWORD(1),
        /** Frames the vanilla stone_sword sprite. */
        STONE_SWORD(2),
        /** Frames the vanilla golden_sword sprite. */
        GOLDEN_SWORD(3),
        /** Frames the vanilla iron_sword sprite. */
        IRON_SWORD(4),
        /** Frames the vanilla diamond_sword sprite. */
        DIAMOND_SWORD(5),
        /** Frames the vanilla netherite_sword sprite. */
        NETHERITE_SWORD(6);

        final int index;

        HeldItem(int index) {
            this.index = index;
        }

        public int index() {
            return this.index;
        }

        public static HeldItem byIndex(int i) {
            return switch (i) {
                case 1 -> WOODEN_SWORD;
                case 2 -> STONE_SWORD;
                case 3 -> GOLDEN_SWORD;
                case 4 -> IRON_SWORD;
                case 5 -> DIAMOND_SWORD;
                case 6 -> NETHERITE_SWORD;
                default -> NONE;
            };
        }
    }

    /** Max keyframe colours carried to the shader (RGBA each). */
    public static final int MAX_COLORS = 8;
    /** GPU header size in vec4 (see class javadoc). */
    public static final int VEC4_PER_EMITTER = 20;

    public final EmitterShape shape;
    /** Shape parameter: box half-extent, sphere radius, or cone height (blocks). */
    public final double size;
    /** Reserved for per-axis/asymmetric shapes; 0 otherwise. */
    public final double radius;
    public final double speedMin;
    public final double speedMax;
    public final double lifeMin;
    public final double lifeMax;
    public final double sizeStart;
    public final double sizeEnd;
    /** Exponent applied to normalised life before size interpolation (ease). */
    public final double sizeEase;
    public final Vec3 gravity;       // blocks/s^2
    public final double drag;        // exponential damping, 1/s
    public final Vec3 acceleration;  // constant blocks/s^2
    public final double windStrength;// blocks/s^2 along windDirection
    public final Vec3 windDirection; // unit-ish; also the cone spray axis
    /** Unit normal used by {@link EmitterShape#PLANE}; zero for other shapes. */
    public final Vec3 planeNormal;
    public final double rotation;    // initial billboard roll, radians (per-particle randomised around it)
    public final double coneTanHalf; // tan(half opening angle) for CONE
    /** Keyframe colours as a flat RGBA array; {@code colors.length/4} entries (2..8). */
    public final float[] colors;
    /** Global additive glow multiplier for this emitter. */
    public final double glow;
    /** Behavior type; its material selects the draw path. */
    public final ParticleTypes.Type type;
    /** Collision behaviour for ALPHA particles. */
    public final CollideMode collideMode;
    /**
     * Vanilla cherry-leaf style flutter amplitude (blocks at end of life).
     * 0 = disabled. The wobble grows as {@code age^1.25} over the lifetime.
     */
    public final double flutter;
    /** Vanilla cherry-leaf random billboard spin (random velocity + acceleration derived from seed). */
    public final boolean spin;
    /** Number of sprite frames in the atlas (1 = single frame / unsprited). */
    public final int spriteCount;
    /** Procedural animation for {@link ParticleTypes.Material#MODEL} emitters. */
    public final Animation animation;
    /**
     * Held item for {@link ParticleTypes.Material#MODEL} emitters (header 17.z): rendered at
     * the vanilla hand anchor when the pose carries it (HOLD animation, or a
     * dive-wave claim overriding the id at render time). {@link HeldItem#NONE}
     * renders nothing; storm specs stay NONE — the wave tier rides the wave
     * uniforms instead, and the two paths must never stack.
     */
    public final HeldItem heldItem;
    /**
     * Lightmap flag for textured (ALPHA/OPAQUE) emitters (header 17.z — the
     * same slot MODEL reads as heldItem, split by material): 1 = the CPU
     * samples {@code LevelRenderer.getLightColor} at the spawn origin into the
     * emit command and the shader multiplies the sprite by the real vanilla
     * lightmap (vanilla {@code Particle.getLightColor} behaviour, e.g. the
     * cherry leaves). 0 = fullbright (vanilla petals are NOT fullbright — this
     * is the cherry-alignment switch).
     */
    public final boolean lightmap;
    /** Additive shader colour mode carried in header 17.x for non-MODEL emitters. */
    public final int shaderColorMode;

    private final float[] packed;

    private EmitterSpec(Builder b) {
        this.shape = Objects.requireNonNull(b.shape, "shape");
        this.size = b.size;
        this.radius = b.radius;
        this.speedMin = b.speedMin;
        this.speedMax = b.speedMax;
        this.lifeMin = b.lifeMin;
        this.lifeMax = b.lifeMax;
        this.sizeStart = b.sizeStart;
        this.sizeEnd = b.sizeEnd;
        this.sizeEase = b.sizeEase;
        this.gravity = b.gravity;
        this.drag = b.drag;
        this.acceleration = b.acceleration;
        this.windStrength = b.windStrength;
        this.windDirection = b.windDirection;
        this.planeNormal = b.planeNormal.lengthSqr() > 1e-12 ? b.planeNormal.normalize() : Vec3.ZERO;
        this.rotation = b.rotation;
        this.coneTanHalf = b.coneTanHalf;
        this.colors = b.colors.length == 0
                ? new float[] { 1f, 1f, 1f, 1f }
                : b.colors.clone();
        this.glow = b.glow;
        this.type = b.type == null ? ParticleTypes.standard(ParticleTypes.Material.ADDITIVE) : b.type;
        ParticleTypes.requireRegistered(this.type);
        this.collideMode = Objects.requireNonNull(b.collideMode, "collideMode");
        this.flutter = b.flutter;
        this.spin = b.spin;
        this.spriteCount = Math.max(1, Math.min(64, b.spriteCount));
        this.animation = Objects.requireNonNull(b.animation, "animation");
        this.heldItem = Objects.requireNonNull(b.heldItem, "heldItem");
        this.lightmap = b.lightmap;
        this.shaderColorMode = Math.max(0, Math.min(255, b.shaderColorMode));
        this.packed = pack();
    }

    public static Builder builder() {
        return new Builder();
    }

    private float[] pack() {
        float[] f = new float[VEC4_PER_EMITTER * 4];
        f[0] = this.type.id(); // reserved header word: behavior id; render ABI is unchanged
        f[0 * 4 + 3] = (float) size;
        f[1 * 4 + 0] = shape.index();
        f[1 * 4 + 1] = (float) speedMin;
        f[1 * 4 + 2] = (float) speedMax;
        f[1 * 4 + 3] = (float) radius;
        f[2 * 4 + 0] = (float) gravity.x;
        f[2 * 4 + 1] = (float) gravity.y;
        f[2 * 4 + 2] = (float) gravity.z;
        f[2 * 4 + 3] = (float) drag;
        f[3 * 4 + 0] = (float) acceleration.x;
        f[3 * 4 + 1] = (float) acceleration.y;
        f[3 * 4 + 2] = (float) acceleration.z;
        f[3 * 4 + 3] = (float) windStrength;
        float wl = (float) windDirection.length();
        f[4 * 4 + 0] = wl > 1e-6f ? (float) (windDirection.x / wl) : 0f;
        f[4 * 4 + 1] = wl > 1e-6f ? (float) (windDirection.y / wl) : 1f;
        f[4 * 4 + 2] = wl > 1e-6f ? (float) (windDirection.z / wl) : 0f;
        f[4 * 4 + 3] = (float) rotation;
        f[5 * 4 + 0] = (float) lifeMin;
        f[5 * 4 + 1] = (float) lifeMax;
        f[5 * 4 + 2] = (float) sizeStart;
        f[5 * 4 + 3] = (float) sizeEnd;
        f[6 * 4 + 0] = (float) sizeEase;
        f[6 * 4 + 1] = (float) coneTanHalf;
        // A single keyframe is valid; the shader handles it as a constant
        // colour. Keep the count within the available complete RGBA frames,
        // and let the loop below pad the remaining GPU slots with the last one.
        int count = Math.max(1, Math.min(MAX_COLORS, colors.length / 4));
        f[6 * 4 + 2] = count;
        f[6 * 4 + 3] = (float) glow;
        // 7: material, collideMode, flutter, spin
        f[7 * 4 + 0] = type.material().index();
        f[7 * 4 + 1] = collideMode.index();
        f[7 * 4 + 2] = (float) flutter;
        f[7 * 4 + 3] = spin ? 1f : 0f;
        // keyframe colours
        for (int i = 0; i < MAX_COLORS; i++) {
            int src = Math.min(i, count - 1) * 4;
            f[(8 + i) * 4 + 0] = colors[src + 0];
            f[(8 + i) * 4 + 1] = colors[src + 1];
            f[(8 + i) * 4 + 2] = colors[src + 2];
            f[(8 + i) * 4 + 3] = colors[src + 3];
        }
        // 16: bakeIndex(lazy; 0 initially), spriteCount, 0, 0
        f[16 * 4 + 0] = 0f;
        f[16 * 4 + 1] = spriteCount;
        f[16 * 4 + 2] = 0f;
        f[16 * 4 + 3] = 0f;
        // 17: animation (MODEL only), shader colour mode (additive), spawnStyle
        // (storm writes post-pack), heldItem (MODEL) / lightMode (textured)
        // — material split, 0
        f[17 * 4 + 0] = type.material() == ParticleTypes.Material.MODEL ? animation.index() : shaderColorMode;
        f[17 * 4 + 2] = type.material() == ParticleTypes.Material.MODEL ? heldItem.index() : (lightmap ? 1f : 0f);
        // 18 stays zero; 19 carries the PLANE basis normal.
        f[19 * 4 + 0] = (float) planeNormal.x;
        f[19 * 4 + 1] = (float) planeNormal.y;
        f[19 * 4 + 2] = (float) planeNormal.z;
        return f;
    }

    /** The packed 20-vec4 GPU header for this spec. */
    public float[] packed() {
        return packed;
    }

    /**
     * Header copy with the animation id written into vec4 #17.x. Used by the
     * runtime live-switch override (emitter header re-upload).
     */
    public float[] packedWithAnimation(int animationIndex) {
        float[] f = packed.clone();
        f[17 * 4] = animationIndex;
        return f;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof EmitterSpec that))
            return false;
        return Arrays.equals(packed, that.packed);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(packed);
    }

    @Override
    public String toString() {
        return "EmitterSpec{" + shape + ", size=" + size + ", life=" + lifeMin + ".." + lifeMax
                + ", type=" + type.name() + ", collide=" + collideMode + '}';
    }

    public static final class Builder {
        private EmitterShape shape = EmitterShape.POINT;
        private double size = 0;
        private double radius = 0;
        private double speedMin = 0;
        private double speedMax = 0;
        private double lifeMin = 1;
        private double lifeMax = 2;
        private double sizeStart = 0.1;
        private double sizeEnd = 0.05;
        private double sizeEase = 1;
        private Vec3 gravity = Vec3.ZERO;
        private double drag = 0;
        private Vec3 acceleration = Vec3.ZERO;
        private double windStrength = 0;
        private Vec3 windDirection = new Vec3(0, 1, 0);
        private Vec3 planeNormal = Vec3.ZERO;
        private double rotation = 0;
        private double coneTanHalf = 0.577f; // ~30 degrees
        private float[] colors = new float[] { 1f, 1f, 1f, 1f };
        private double glow = 1;
        private ParticleTypes.Type type;
        public Builder type(ParticleTypes.Type value) { this.type = Objects.requireNonNull(value, "type"); return this; }
        private CollideMode collideMode = CollideMode.NONE;
        private double flutter = 0;
        private boolean spin = false;
        private int spriteCount = 1;
        private Animation animation = Animation.FLY;
        private HeldItem heldItem = HeldItem.NONE;
        private boolean lightmap = false;
        private int shaderColorMode = 0;

        public Builder shape(EmitterShape v) { this.shape = v; return this; }
        public Builder size(double v) { this.size = v; return this; }
        public Builder radius(double v) { this.radius = v; return this; }
        public Builder speed(double min, double max) { this.speedMin = min; this.speedMax = max; return this; }
        public Builder life(double min, double max) { this.lifeMin = min; this.lifeMax = max; return this; }
        public Builder sizeOverLife(double start, double end, double ease) {
            this.sizeStart = start; this.sizeEnd = end; this.sizeEase = ease; return this;
        }
        public Builder gravity(double x, double y, double z) { this.gravity = new Vec3(x, y, z); return this; }
        public Builder drag(double v) { this.drag = v; return this; }
        public Builder acceleration(double x, double y, double z) { this.acceleration = new Vec3(x, y, z); return this; }
        public Builder wind(double strength, double x, double y, double z) {
            this.windStrength = strength; this.windDirection = new Vec3(x, y, z); return this;
        }
        /** Sets the square-plane orientation used by {@link EmitterShape#PLANE}. */
        public Builder planeNormal(double x, double y, double z) {
            this.planeNormal = new Vec3(x, y, z); return this;
        }
        public Builder rotation(double v) { this.rotation = v; return this; }
        public Builder cone(double tanHalfAngle) { this.coneTanHalf = tanHalfAngle; return this; }
        public Builder glow(double v) { this.glow = v; return this; }

        /** Appends one RGBA keyframe (values 0..1). Up to {@link #MAX_COLORS}. */
        public Builder color(double r, double g, double b, double a) {
            if (colors.length < MAX_COLORS * 4) {
                float[] next = Arrays.copyOf(colors, Math.min(MAX_COLORS * 4, colors.length + 4));
                next[colors.length] = (float) r;
                next[colors.length + 1] = (float) g;
                next[colors.length + 2] = (float) b;
                next[colors.length + 3] = (float) a;
                colors = next;
            }
            return this;
        }

        /** Replaces the keyframe list with the given RGBA frames. */
        public Builder colors(float[] rgba) {
            if (rgba != null && rgba.length >= 4) {
                int kept = Math.max(4, Math.min(MAX_COLORS * 4, rgba.length));
                this.colors = Arrays.copyOf(rgba, kept);
            }
            return this;
        }

        public Builder collide(CollideMode v) { this.collideMode = v; return this; }
        /** Vanilla-style flutter amplitude (blocks at full life); 0 disables. */
        public Builder flutter(double v) { this.flutter = v; return this; }
        public Builder spin(boolean v) { this.spin = v; return this; }
        public Builder spriteCount(int v) { this.spriteCount = v; return this; }
        /** Procedural animation for MODEL emitters (default FLY). */
        public Builder animation(Animation v) { this.animation = v; return this; }
        /** Held item rendered at the vanilla hand anchor (default NONE). */
        public Builder heldItem(HeldItem v) { this.heldItem = v; return this; }
        /**
         * Textured emitters only: multiply the sprite by the real vanilla
         * lightmap sampled at the spawn point (vanilla {@code Particle
         * .getLightColor} behaviour). Reserved header slot 17.z, shared with
         * heldItem — which is itself MODEL-only, so the two never collide.
         */
        public Builder lightmap(boolean v) { this.lightmap = v; return this; }
        /** Sets the additive vertex-shader colour mode carried in header 17.x. */
        public Builder shaderColorMode(int v) { this.shaderColorMode = v; return this; }

        public EmitterSpec build() {
            return new EmitterSpec(this);
        }
    }
}
