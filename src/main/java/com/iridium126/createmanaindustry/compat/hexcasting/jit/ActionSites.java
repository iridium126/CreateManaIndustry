package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import at.petrak.hexcasting.api.casting.castables.Action;
import at.petrak.hexcasting.api.casting.ActionRegistryEntry;
import at.petrak.hexcasting.xplat.IXplatAbstractions;
import com.iridium126.createmanaindustry.config.ServerConfig;
import java.util.IdentityHashMap;
import net.minecraft.resources.ResourceKey;

/** Only registry-owned ordinary actions enter this cache; never special-handler instances. */
public final class ActionSites {
    private static final long[] UNCOMPILED = new long[0];
    private static final IdentityHashMap<Action, long[]> SITES = new IdentityHashMap<>();
    private static final IdentityHashMap<ResourceKey<ActionRegistryEntry>, Action> ACTIONS_BY_KEY = new IdentityHashMap<>();
    private static long epoch = -1;
    private static long compiledHits;
    private static int siteCount;
    private static boolean registryLoaded;
    private static final String ARGS = "(Lat/petrak/hexcasting/api/casting/eval/CastingEnvironment;"
            + "Lat/petrak/hexcasting/api/casting/eval/vm/CastingImage;"
            + "Lat/petrak/hexcasting/api/casting/eval/vm/SpellContinuation;";
    private static final CallCompiler.Description NORMAL = new CallCompiler.Description(
            "at/petrak/hexcasting/api/casting/castables/Action", "operate", ARGS
            + ")Lat/petrak/hexcasting/api/casting/eval/OperationResult;", true);
    private static final CallCompiler.Description PARENS = new CallCompiler.Description(
            "at/petrak/hexcasting/api/casting/castables/Action", "operateInParens", ARGS
            + "Lat/petrak/hexcasting/api/casting/iota/Iota;)Lat/petrak/hexcasting/api/casting/eval/ParenthesizedOperationResult;", true);
    private ActionSites() {}
    /** Uses only registry-owned key/action identities and is invalidated with compiled sites. */
    public static Action registeredAction(ResourceKey<ActionRegistryEntry> key) {
        if (ServerConfig.hexJitMode == ServerConfig.HexJitMode.OFF
                || !HexJitRuntime.onServerThread() || !JitCompatibility.ready()) return null;
        if (epoch != HexJitRuntime.generation()) { clear(); epoch = HexJitRuntime.generation(); }
        loadRegistry();
        return ACTIONS_BY_KEY.get(key);
    }

    public static CompiledCall acquire(Action action, boolean parens) {
        if (!HexJitRuntime.enabled()) return null;
        if (epoch != HexJitRuntime.generation()) { clear(); epoch = HexJitRuntime.generation(); }
        loadRegistry();
        // Dynamic special-handler actions are newly created and are intentionally interpreted.
        long[] ids = SITES.get(action);
        if (ids == null) return null;
        if (ids == UNCOMPILED) {
            if (siteCount >= ServerConfig.hexJitMaxUnits) {
                for (var entry : SITES.entrySet()) entry.setValue(UNCOMPILED);
                siteCount = 0;
            }
            ids = new long[] {HexJitRuntime.nextSite(), HexJitRuntime.nextSite()};
            SITES.put(action, ids);
            siteCount++;
        }
        CompiledCall code = HexJitRuntime.acquire(ids[parens ? 1 : 0], parens ? PARENS : NORMAL);
        if (code != null) compiledHits++;
        return code;
    }
    public static long compiledHits() { return compiledHits; }
    public static void clear() {
        SITES.clear(); ACTIONS_BY_KEY.clear(); registryLoaded = false; compiledHits = 0; siteCount = 0;
    }

    private static void loadRegistry() {
        if (registryLoaded) return;
        var registry = IXplatAbstractions.INSTANCE.getActionRegistry();
        for (var key : registry.registryKeySet()) {
            ActionRegistryEntry entry = registry.get(key);
            if (entry == null) continue;
            Action action = entry.action();
            SITES.put(action, UNCOMPILED);
            ACTIONS_BY_KEY.put(key, action);
        }
        registryLoaded = true;
    }
}
