package com.iridium126.createmanaindustry.dimension.gen.worldtree;

import com.iridium126.createmanaindustry.worldgen.markov.EpicRedwoodModel;
import com.iridium126.createmanaindustry.worldgen.markov.RedwoodVolume;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/** Shared by the vanilla and cubic generators; lifetime follows their world, not a static seed cache. */
final class EpicRedwoodTree {
    private static final Map<Integer, WeakReference<EpicRedwoodTree>> TREES = new HashMap<>();
    private final int seed;
    private volatile RedwoodVolume volume;

    private EpicRedwoodTree(int seed) { this.seed = seed; }

    static synchronized EpicRedwoodTree forSeed(long worldSeed) {
        // MarkovJunior uses signed 32-bit System.Random seeds. Preserve all matching signed seeds.
        int seed = (int)worldSeed;
        TREES.values().removeIf(reference -> reference.get() == null);
        var reference = TREES.get(seed);
        EpicRedwoodTree tree = reference == null ? null : reference.get();
        if (tree == null) { tree = new EpicRedwoodTree(seed); TREES.put(seed, new WeakReference<>(tree)); }
        return tree;
    }

    RedwoodVolume volume() {
        RedwoodVolume result = volume;
        if (result != null) return result;
        synchronized (this) {
            if (volume == null) volume = ModelHolder.MODEL.generate(seed);
            return volume;
        }
    }

    RedwoodVolume readyVolume() { return volume; }

    private static final class ModelHolder {
        static final EpicRedwoodModel MODEL = load();
        private static EpicRedwoodModel load() {
            try (var stream = EpicRedwoodTree.class.getResourceAsStream("/data/createmanaindustry/markov/epic_redwood_3072.xml")) {
                if (stream == null) throw new IllegalStateException("Missing EpicRedwood3072 XML");
                return new EpicRedwoodModel(stream);
            } catch (java.io.IOException e) { throw new IllegalStateException("Cannot load EpicRedwood3072", e); }
        }
    }
}
