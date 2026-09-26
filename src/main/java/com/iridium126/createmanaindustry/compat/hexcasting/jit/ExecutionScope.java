package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import at.petrak.hexcasting.api.casting.ParticleSpray;
import at.petrak.hexcasting.api.pigment.FrozenPigment;
import com.iridium126.createmanaindustry.config.ServerConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Optional observer bookkeeping. Scopes hold booleans only and are removed in finally blocks. */
public final class ExecutionScope implements AutoCloseable {
    private static final ThreadLocal<ExecutionScope> CURRENT = new ThreadLocal<>();
    private static long lastMotionPushes;
    private static long lastMotionWrites;
    private final ExecutionScope previous;
    private MotionState motions;
    private boolean compiled;
    private boolean notifying;
    private boolean addMotionEffect;
    private final boolean batchMotion;
    private final boolean fastSpecialHandlerMath;
    private Map<ParticleSpray, List<PigmentSnapshot>> emittedParticles;
    private ParticleSpray lastSpray;
    private PigmentSnapshot lastPigment;
    private boolean hasLastParticle;
    private ExecutionScope(boolean batchMotion, boolean fastSpecialHandlerMath) {
        previous = CURRENT.get();
        motions = previous == null ? null : previous.motions;
        this.batchMotion = batchMotion || previous != null && previous.batchMotion;
        this.fastSpecialHandlerMath = fastSpecialHandlerMath || previous != null && previous.fastSpecialHandlerMath;
        if (previous == null) {
            lastMotionPushes = 0;
            lastMotionWrites = 0;
        }
        CURRENT.set(this);
    }
    public static ExecutionScope enter(boolean batchMotion, boolean fastSpecialHandlerMath) {
        return new ExecutionScope(batchMotion, fastSpecialHandlerMath);
    }
    public void startStep() { compiled = false; notifying = false; }
    public void notifying(boolean value) { notifying = value; }
    public static void markCompiled() {
        if (!ServerConfig.hexJitSkipObservers) return;
        ExecutionScope scope = CURRENT.get();
        if (scope != null) scope.compiled = true;
    }
    public static ExecutionScope current() { return CURRENT.get(); }
    public static long lastMotionPushes() { return lastMotionPushes; }
    public static long lastMotionWrites() { return lastMotionWrites; }
    public void addMotionEffect(boolean value) { addMotionEffect = value; }
    public boolean inAddMotionEffect() { return addMotionEffect; }
    public boolean batchMotionEnabled() { return batchMotion; }
    public boolean fastSpecialHandlerMathEnabled() { return fastSpecialHandlerMath; }

    /** Preserve each Vec3.add rounding step while deferring only Entity's field writes and allocations. */
    public void accumulateMotion(Entity entity, double x, double y, double z) {
        MotionState state = motionState();
        PendingMotion pending = state.get(entity);
        if (pending == null) {
            Vec3 current = entity.getDeltaMovement();
            pending = new PendingMotion(current.x, current.y, current.z);
            state.put(entity, pending);
        }
        pending.add(x, y, z);
        state.pushes++;
    }

    /** Null means no buffered motion exists for this entity. The returned vector keeps stable identity until a push. */
    public Vec3 pendingMotion(Entity entity) {
        MotionState state = motions;
        PendingMotion pending = state == null ? null : state.get(entity);
        return pending == null ? null : pending.view();
    }

    /** Commit before an explicit setter or a method that reads Entity's private velocity field directly. */
    public void flushMotion(Entity entity) {
        MotionState state = motions;
        if (state == null) return;
        PendingMotion pending = state.remove(entity);
        if (pending != null) {
            entity.setDeltaMovement(pending.view());
            state.writes++;
        }
    }

    public void flushAllMotion() {
        MotionState state = motions;
        while (state != null && state.hasPending()) flushMotion(state.nextEntity());
    }

    public static boolean maySkip() {
        ExecutionScope scope = CURRENT.get();
        return scope != null && scope.compiled && scope.notifying;
    }

    /** Returns true when this exact spray/pigment pair should still be sent to clients. */
    public boolean emitParticle(ParticleSpray spray, FrozenPigment pigment) {
        if (!ServerConfig.hexJitCoalesceDecorations) {
            return true;
        }
        if (hasLastParticle && spray.equals(lastSpray) && lastPigment.matches(pigment)) {
            return false;
        }
        if (emittedParticles == null) emittedParticles = new HashMap<>();
        List<PigmentSnapshot> colors = emittedParticles.get(spray);
        if (colors != null) {
            for (PigmentSnapshot color : colors) {
                if (color.matches(pigment)) {
                    lastSpray = spray;
                    lastPigment = color;
                    hasLastParticle = true;
                    return false;
                }
            }
        } else {
            colors = new ArrayList<>(1);
            emittedParticles.put(spray, colors);
        }
        PigmentSnapshot snapshot = PigmentSnapshot.of(pigment);
        colors.add(snapshot);
        lastSpray = spray;
        lastPigment = snapshot;
        hasLastParticle = true;
        return true;
    }

    private record PigmentSnapshot(ItemStack item, UUID owner, boolean isNull) {
        private static PigmentSnapshot of(FrozenPigment pigment) {
            return pigment == null ? new PigmentSnapshot(null, null, true)
                    : new PigmentSnapshot(pigment.item().copy(), pigment.owner(), false);
        }
        private boolean matches(FrozenPigment pigment) {
            if (isNull) return pigment == null;
            return pigment != null && owner.equals(pigment.owner()) && ItemStack.matches(item, pigment.item());
        }
    }

    @Override public void close() {
        try {
            if (previous == null) flushAllMotion();
            if (emittedParticles != null) emittedParticles.clear();
        } finally {
            if (previous == null) {
                lastMotionPushes = motions == null ? 0 : motions.pushes;
                lastMotionWrites = motions == null ? 0 : motions.writes;
            }
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }

    private MotionState motionState() {
        MotionState state = motions;
        if (state == null) {
            state = new MotionState();
            for (ExecutionScope scope = this; scope != null; scope = scope.previous) scope.motions = state;
        }
        return state;
    }

    private static final class MotionState {
        private Entity singleEntity;
        private PendingMotion singlePending;
        private IdentityHashMap<Entity, PendingMotion> additional;
        private long pushes;
        private long writes;

        private PendingMotion get(Entity entity) {
            if (singleEntity == entity) return singlePending;
            return additional == null ? null : additional.get(entity);
        }
        private void put(Entity entity, PendingMotion motion) {
            if (singleEntity == entity) {
                singlePending = motion;
            } else if (additional != null && additional.containsKey(entity)) {
                additional.put(entity, motion);
            } else if (singlePending == null) {
                singleEntity = entity;
                singlePending = motion;
            } else {
                if (additional == null) additional = new IdentityHashMap<>();
                additional.put(entity, motion);
            }
        }
        private PendingMotion remove(Entity entity) {
            if (singleEntity == entity) {
                PendingMotion result = singlePending;
                singleEntity = null;
                singlePending = null;
                return result;
            }
            if (additional == null) return null;
            PendingMotion result = additional.remove(entity);
            if (additional.isEmpty()) additional = null;
            return result;
        }
        private boolean hasPending() { return singlePending != null || additional != null && !additional.isEmpty(); }
        private Entity nextEntity() {
            return singlePending != null ? singleEntity : additional.keySet().iterator().next();
        }
    }

    private static final class PendingMotion {
        private double x, y, z;
        private Vec3 view;
        private PendingMotion(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
        private void add(double dx, double dy, double dz) {
            x += dx;
            y += dy;
            z += dz;
            view = null;
        }
        private Vec3 view() {
            if (view == null) view = new Vec3(x, y, z);
            return view;
        }
    }
}
