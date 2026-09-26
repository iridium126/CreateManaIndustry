package com.iridium126.createmanaindustry.client.particles.engine;

import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.api.client.HexPatternRenderHolder;
import at.petrak.hexcasting.api.pigment.ColorProvider;
import at.petrak.hexcasting.api.pigment.FrozenPigment;
import at.petrak.hexcasting.client.ClientTickCounter;
import at.petrak.hexcasting.client.render.RenderLib;
import at.petrak.hexcasting.xplat.IClientXplatAbstractions;
import at.petrak.hexcasting.xplat.IXplatAbstractions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.BufferUtils;
import java.nio.ByteBuffer;
import java.util.*;

/** Optional Hex bridge. All per-frame work is scalar state synchronization; geometry is cached. */
final class HexPatternRuntime {
    private final HexPatternBuffers gpu;
    private final IdentityHashMap<HexPatternRenderHolder, Entry> entries = new IdentityHashMap<>();
    private final Map<HexPattern, Shape> shapes = new HashMap<>();
    private final Map<UUID, Owner> owners = new HashMap<>();
    private final Set<UUID> redirected = new HashSet<>();
    private final ArrayDeque<Integer> free = new ArrayDeque<>();
    private final int[] generations = new int[HexPatternBuffers.SLOTS];
    private final Map<List<Integer>, Palette> palettes = new HashMap<>();
    private int high, frame, count, spawnEstimate;
    private boolean resourcesDirty = true;
    private ClientLevel level;

    HexPatternRuntime(HexPatternBuffers gpu) { this.gpu = gpu; }

    boolean redirected(Player player) { return player.level() == level && redirected.contains(player.getUUID()); }
    int count() { return count; }
    int spawnEstimate() { return spawnEstimate; }

    int prepare(net.minecraft.client.DeltaTracker deltaTracker, net.minecraft.client.Camera camera, int budget, boolean enabled) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel now = minecraft.level;
        if (level != now) { reset(); level = now; }
        redirected.clear();
        frame++;
        count = 0;
        spawnEstimate = 0;
        int anchor = 0;
        ByteBuffer b = gpu.input;
        if (enabled && level != null) for (Player player : level.players()) {
            // Match LevelRenderer's first-person camera-entity rule.
            if (player == camera.getEntity() && !camera.isDetached() && !player.isSleeping()) continue;
            float partialTick = deltaTracker.getGameTimeDeltaPartialTick(!level.tickRateManager().isEntityFrozen(player));
            var holders = IClientXplatAbstractions.INSTANCE.getClientCastingStack(player).getPatterns();
            if (holders.isEmpty()) continue;
            // Whole-owner fallback, so overflow cannot hide only part of a casting stack.
            if (holders.size() + count > Math.min(budget, HexPatternBuffers.SLOTS)) continue;
            boolean fits = true;
            for (var holder : holders) {
                Shape shape = shapes.get(holder.getPattern());
                if (shape == null) {
                    shape = new Shape(holder.getPattern());
                    shapes.put(holder.getPattern(), shape);
                    resourcesDirty = true;
                }
                if (shape.vertexCount > HexPatternBuffers.VERTEX_STRIDE) fits = false;
            }
            if (!fits) continue;
            Owner owner = owners.computeIfAbsent(player.getUUID(), key -> new Owner());
            owner.seen = frame;
            FrozenPigment pigment = IXplatAbstractions.INSTANCE.getPigment(player);
            if (!Objects.equals(pigment, owner.pigment)) {
                owner.pigment = pigment;
                owner.provider = pigment.getColorProvider();
                var description = HexPigmentColors.describe(owner.provider);
                owner.divisor = description == null ? -1 : description.divisor();
                owner.palette = null;
                if (description != null) {
                    List<Integer> key = Arrays.stream(description.colors()).boxed().toList();
                    owner.palette = palettes.computeIfAbsent(key, colors -> {
                        resourcesDirty = true;
                        return new Palette(colors);
                    });
                }
            }
            // Entity rendering interpolates xOld/yOld/zOld, not Entity.getPosition's xo/yo/zo.
            Vec3 offset = minecraft.getEntityRenderDispatcher().getRenderer(player).getRenderOffset(player, partialTick);
            double x = player.tickCount == 0 ? player.getX() : net.minecraft.util.Mth.lerp(partialTick, player.xOld, player.getX());
            double y = player.tickCount == 0 ? player.getY() : net.minecraft.util.Mth.lerp(partialTick, player.yOld, player.getY());
            double z = player.tickCount == 0 ? player.getZ() : net.minecraft.util.Mth.lerp(partialTick, player.zOld, player.getZ());
            int ab = (HexPatternBuffers.ANCHOR_BASE + anchor) * 16;
            b.putFloat(ab, (float) (x + offset.x)).putFloat(ab + 4, (float) (y + offset.y)).putFloat(ab + 8, (float) (z + offset.z)).putFloat(ab + 12, (float) level.getGameTime() + partialTick);
            int k = 0;
            for (var holder : holders) {
                Entry e = entries.get(holder);
                if (e == null) {
                    int slot = free.isEmpty() ? high++ : free.removeFirst();
                    // Admission above bounds active entries; reclaim unseen previous-frame entries if needed.
                    if (slot >= HexPatternBuffers.SLOTS) { high--; fits = false; break; }
                    int generation = (generations[slot] + 1) & 0xfffff;
                    if (generation == 0) generation = 1;
                    generations[slot] = generation;
                    e = new Entry(slot, generation, holder.getColourPos(player.getRandom()), shapes.get(holder.getPattern()));
                    entries.put(holder, e);
                    spawnEstimate++;
                }
                e.seen = frame;
                e.owner = owner;
                e.anchor = anchor;
                if (e.order != k) {
                    e.order = k;
                    e.spinA = (float) Math.sin(k * 12.543565f) * 3.4f;
                    e.spinB = k / 12.43f;
                }
                k++;
                e.remaining = holder.getLifetime();
                e.seed = player.hashCode();
                e.shape.seen = frame;
                if (owner.palette != null) owner.palette.seen = frame;
                if (owner.divisor < 0)
                    e.fallback = HexPigmentColors.sample(owner.provider, ClientTickCounter.getTotal() / 2f, e.colorPos);
            }
            if (fits) { redirected.add(player.getUUID()); count += holders.size(); }
            else for (var holder : holders) { Entry e = entries.get(holder); if (e != null) e.seen = -1; }
            anchor++;
        }
        var iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry e = iterator.next().getValue();
            if (e.seen != frame) {
                b.putInt((1 + e.slot * HexPatternBuffers.META_ROWS) * 16, 0);
                free.add(e.slot);
                iterator.remove();
            }
        }
        while (high > 0 && free.remove(Integer.valueOf(high - 1))) high--;
        owners.values().removeIf(o -> o.seen != frame);
        if (shapes.values().removeIf(s -> s.seen != frame)) resourcesDirty = true;
        if (palettes.values().removeIf(p -> p.seen != frame)) resourcesDirty = true;
        if (resourcesDirty) uploadResources();
        int outputPoint = 0;
        for (Entry e : entries.values()) {
            int base = (1 + e.slot * HexPatternBuffers.META_ROWS) * 16;
            b.putInt(base, (e.generation << 12) | e.slot);
            b.putInt(base + 4, e.shape.offset).putInt(base + 8, e.shape.points.size()).putInt(base + 12, e.anchor);
            b.putFloat(base + 16, e.remaining).putFloat(base + 20, e.order).putInt(base + 24, e.seed).putFloat(base + 28, 3.5f);
            b.putFloat(base + 32, (float) e.colorPos.x).putFloat(base + 36, (float) e.colorPos.y).putFloat(base + 40, (float) e.colorPos.z).putInt(base + 44, e.fallback);
            Palette p = e.owner.palette;
            b.putInt(base + 48, p == null ? 0 : p.offset).putInt(base + 52, p == null ? 0 : p.colors.size()).putFloat(base + 56, e.owner.divisor).putFloat(base + 60, 0);
            b.putInt(base + 64, outputPoint).putInt(base + 68, e.shape.vertexCount).putFloat(base + 72, e.spinA).putFloat(base + 76, e.spinB);
            outputPoint += e.shape.points.size();
        }
        b.putFloat(0, high).putFloat(4, level == null ? 0 : (float) level.getGameTime());
        b.putFloat(8, ClientTickCounter.getTotal() / 2f).putFloat(12, ClientTickCounter.getTotal());
        gpu.ensurePoints(outputPoint);
        gpu.uploadInputs(high, anchor);
        return high;
    }

    void reset() {
        entries.clear(); shapes.clear(); owners.clear(); palettes.clear(); redirected.clear(); free.clear();
        // Keep generations across world transitions so old pool entries cannot attach to new holders.
        high = count = 0;
        for (int i = 0; i < HexPatternBuffers.SLOTS; i++) gpu.input.putInt((1 + i * HexPatternBuffers.META_ROWS) * 16, 0);
        resourcesDirty = true;
    }

    private void uploadResources() {
        int rows = 256;
        for (Shape s : shapes.values()) { s.offset = rows; rows += s.points.size() * 2; }
        for (Palette p : palettes.values()) { p.offset = rows; rows += p.colors.size(); }
        ByteBuffer data = BufferUtils.createByteBuffer(rows * 16);
        // SimplexNoise(SingleThreadedRandomSource(9001)): three origin doubles, then Fisher-Yates.
        Random random = new Random(9001L);
        random.nextDouble(); random.nextDouble(); random.nextDouble();
        int[] permutation = new int[256];
        for (int i = 0; i < 256; i++) permutation[i] = i;
        for (int i = 0; i < 256; i++) { int j = i + random.nextInt(256 - i); int v = permutation[i]; permutation[i] = permutation[j]; permutation[j] = v; }
        for (int v : permutation) data.putFloat(v).putFloat(0).putFloat(0).putFloat(0);
        for (Shape s : shapes.values()) for (float[] point : s.points) for (float value : point) data.putFloat(value);
        for (Palette p : palettes.values()) for (int color : p.colors)
            data.putFloat((color >> 16) & 255).putFloat((color >> 8) & 255).putFloat(color & 255).putFloat(255);
        data.flip(); gpu.uploadResources(data); resourcesDirty = false;
    }

    private static final class Owner {
        FrozenPigment pigment; ColorProvider provider; Palette palette; float divisor; int seen;
    }
    private static final class Palette {
        final List<Integer> colors; int offset, seen;
        Palette(List<Integer> colors) { this.colors = colors; }
    }
    private static final class Entry {
        final int slot, generation; final Vec3 colorPos; final Shape shape;
        Owner owner; int seen, anchor, order, remaining, seed, fallback; float spinA, spinB;
        Entry(int slot, int generation, Vec3 colorPos, Shape shape) { this.slot = slot; this.generation = generation; this.colorPos = colorPos; this.shape = shape; }
    }
    private static final class Shape {
        final List<float[]> points = new ArrayList<>();
        final int vertexCount;
        int offset, seen;
        Shape(HexPattern pattern) {
            Vec2 center = pattern.getCenter(1f);
            float dx = 0, dy = 0;
            for (Vec2 point : pattern.toLines(1f, Vec2.ZERO)) { dx = Math.max(dx, Math.abs(point.x - center.x)); dy = Math.max(dy, Math.abs(point.y - center.y)); }
            float scale = Math.min(3.8f, Math.min(6.4f / dx, 6.4f / dy));
            var bare = pattern.toLines(scale, pattern.getCenter(scale).negated());
            var duplicates = RenderLib.findDupIndices(pattern.positions());
            int segment = 0;
            for (int i = 0; i < bare.size() - 1; i++) {
                Vec2 a = bare.get(i), z = bare.get(i + 1);
                if (i == 0 || duplicates.contains(i)) {
                    segment = 0;
                    points.add(new float[]{a.x, -a.y, a.x, -a.y, 0, 0, 0, 0});
                }
                float variance = (float) Math.sqrt(a.distanceToSqr(z)) / 5f * 0.65f;
                for (int j = 1; j <= 5; j++) points.add(new float[]{a.x, -a.y, z.x, -z.y, segment, j / 6f, variance, j});
                points.add(new float[]{z.x, -z.y, z.x, -z.y, 0, 0, 0, 0});
                segment++;
            }
            vertexCount = Math.max(0, (points.size() - 1) * 42 + 60) * 2;
        }
    }
}
