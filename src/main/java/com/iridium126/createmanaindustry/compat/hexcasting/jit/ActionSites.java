package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import at.petrak.hexcasting.api.casting.castables.Action;
import at.petrak.hexcasting.api.casting.ActionRegistryEntry;
import at.petrak.hexcasting.xplat.IXplatAbstractions;
import java.util.IdentityHashMap;

/** Only registry-owned ordinary actions enter this cache; never special-handler instances. */
public final class ActionSites {
    private static final IdentityHashMap<Action, long[]> SITES = new IdentityHashMap<>();
    private static final IdentityHashMap<Action, Boolean> REGISTERED = new IdentityHashMap<>();
    private static long epoch = -1;
    private static long compiledHits;
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
    public static CompiledCall acquire(Action action, boolean parens) {
        if ((!HexJitConfig.compileActions && HexJitConfig.mode != HexJitConfig.Mode.PROFILE) || !HexJitRuntime.enabled()) return null;
        if (epoch != HexJitRuntime.generation()) { clear(); epoch = HexJitRuntime.generation(); }
        if (!registryLoaded) {
            var registry = IXplatAbstractions.INSTANCE.getActionRegistry();
            for (var key : registry.registryKeySet()) {
                ActionRegistryEntry entry = registry.get(key);
                if (entry != null) REGISTERED.put(entry.action(), Boolean.TRUE);
            }
            registryLoaded = true;
        }
        // Dynamic special-handler actions are newly created and are intentionally interpreted.
        if (!REGISTERED.containsKey(action)) return null;
        long[] ids = SITES.get(action);
        if (ids == null) {
            if (SITES.size() >= HexJitConfig.maxUnits) clear();
            ids = new long[] {HexJitRuntime.nextSite(), HexJitRuntime.nextSite()};
            SITES.put(action, ids);
        }
        CompiledCall code = HexJitRuntime.acquire(ids[parens ? 1 : 0], parens ? PARENS : NORMAL);
        if (code != null) compiledHits++;
        return code;
    }
    public static long compiledHits() { return compiledHits; }
    public static void clear() { SITES.clear(); REGISTERED.clear(); registryLoaded = false; compiledHits = 0; }
}
