package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.addldata.ADMediaHolder;
import at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.ModList;

/** Uses HexOP's first-priority personal pool without scanning unrelated inventory slots. */
public final class FastHexOPMediaPool {
    private static final ThreadLocal<Pending> PENDING = new ThreadLocal<>();
    private static final ClassValue<Boolean> STANDARD_STAFF_ENVIRONMENT = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("postExecution", at.petrak.hexcasting.api.casting.eval.CastResult.class)
                                .getDeclaringClass() == StaffCastEnv.class
                        && type.getMethod("extractMediaEnvironment", long.class, boolean.class)
                                .getDeclaringClass() == StaffCastEnv.class;
            } catch (ReflectiveOperationException ignored) {
                return false;
            }
        }
    };
    private static volatile Access access;
    private static volatile boolean accessResolved;

    private FastHexOPMediaPool() {}

    public static boolean begin(CastingEnvironment env) {
        if (!JitCompatibility.mediaPoolTargetReady() || !(env instanceof StaffCastEnv)
                || !(env instanceof CastingEnvironmentObserverAccess hooks)
                || !STANDARD_STAFF_ENVIRONMENT.get(env.getClass())
                || !hooks.cmi$getPostExecutions().isEmpty()
                || !hooks.cmi$getPreMediaExtract().isEmpty()
                || !hooks.cmi$getPostMediaExtract().isEmpty()) return false;

        if (!(env.getCastingEntity() instanceof ServerPlayer player)) return false;
        Access api = access();
        if (api == null) return false;
        try {
            if (!(boolean) api.enabled().invokeExact((Player) player)) return false;
            Object holder = (Object) api.get().invokeExact((Player) player);
            if (!(holder instanceof ADMediaHolder media) || !media.canProvide()) return false;
            ExecutionScope scope = ExecutionScope.current();
            Pending pending = PENDING.get();
            if (pending != null && pending.active) return false;
            if (scope != null && pending != null && pending.scope == scope) {
                pending.prepare(env, player, media);
            } else {
                PENDING.set(new Pending(env, player, media, scope));
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Called by the guarded PlayerBasedCastEnv wrapper before it scans inventory sources. */
    public static boolean beginExtraction(CastingEnvironment env, long cost) {
        Pending pending = PENDING.get();
        if (pending == null || !pending.active || pending.env() != env || cost <= 0) return false;
        if (pending.coverageChecked && pending.checkedCost == cost) {
            pending.coverageChecked = false;
            return pending.forced() == pending.holder();
        }
        ADMediaHolder holder = pending.holder();
        if (!holder.canProvide() || holder.withdrawMedia(-1, true) < cost) return false;
        pending.forced(holder);
        return true;
    }

    /** The allow-overcast flag is irrelevant when the personal pool covers the full request. */
    public static boolean skipOvercastCheckWhenCovered(CastingEnvironment env, long cost) {
        Pending pending = PENDING.get();
        if (pending == null || !pending.active || pending.env() != env || cost <= 0) return false;
        pending.coverageChecked = true;
        pending.checkedCost = cost;
        ADMediaHolder holder = pending.holder();
        if (!holder.canProvide() || holder.withdrawMedia(-1, true) < cost) {
            pending.forced(null);
            return false;
        }
        pending.forced(holder);
        return true;
    }

    /** A null result asks the mixin to run Hexcasting's original inventory/Curios scan. */
    public static List<ADMediaHolder> forcedSources(CastingEnvironment env, ServerPlayer player) {
        Pending pending = PENDING.get();
        ADMediaHolder forced = pending == null ? null : pending.forced();
        return pending != null && pending.active && pending.env() == env && pending.player() == player && forced != null
                ? pending.sources() : null;
    }

    public static void endExtraction(CastingEnvironment env) {
        Pending pending = PENDING.get();
        if (pending != null && pending.env() == env) {
            pending.forced(null);
            pending.coverageChecked = false;
        }
    }

    public static void end(CastingEnvironment env) {
        Pending pending = PENDING.get();
        if (pending != null && pending.env() == env) {
            pending.forced(null);
            if (pending.scope == null) PENDING.remove();
            else pending.active = false;
        }
    }

    /** Clear a cast-scoped reusable extraction context when its execution scope closes. */
    public static void endCast(ExecutionScope scope) {
        Pending pending = PENDING.get();
        if (pending != null && pending.scope == scope) PENDING.remove();
    }

    private static Access access() {
        if (accessResolved) return access;
        synchronized (FastHexOPMediaPool.class) {
            if (accessResolved) return access;
            accessResolved = true;
            if (!ModList.get().isLoaded("hexoverpowered")) return null;
            try {
                Class<?> type = Class.forName("io.yukkuric.hexop.personal_mana.PersonalManaHolder");
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                MethodHandle enabled = lookup.findStatic(type, "enablesManaForPlayer",
                        MethodType.methodType(boolean.class, Player.class));
                MethodHandle get = lookup.findStatic(type, "get",
                        MethodType.methodType(type, Player.class))
                        .asType(MethodType.methodType(Object.class, Player.class));
                access = new Access(enabled, get);
            } catch (ReflectiveOperationException | LinkageError ignored) {
                access = null;
            }
            return access;
        }
    }

    private record Access(MethodHandle enabled, MethodHandle get) {}

    private static final class Pending {
        private CastingEnvironment env;
        private ServerPlayer player;
        private ADMediaHolder holder;
        private List<ADMediaHolder> sources;
        private final ExecutionScope scope;
        private ADMediaHolder forced;
        private boolean active = true;
        private boolean coverageChecked;
        private long checkedCost;

        private Pending(CastingEnvironment env, ServerPlayer player, ADMediaHolder holder, ExecutionScope scope) {
            this.scope = scope;
            prepare(env, player, holder);
        }

        private void prepare(CastingEnvironment env, ServerPlayer player, ADMediaHolder holder) {
            if (this.holder != holder) sources = List.of(holder);
            this.env = env;
            this.player = player;
            this.holder = holder;
            this.forced = null;
            this.active = true;
            this.coverageChecked = false;
            this.checkedCost = 0;
        }

        private CastingEnvironment env() { return env; }
        private ServerPlayer player() { return player; }
        private ADMediaHolder holder() { return holder; }
        private List<ADMediaHolder> sources() { return sources; }
        private ADMediaHolder forced() { return forced; }
        private void forced(ADMediaHolder forced) { this.forced = forced; }
    }
}
