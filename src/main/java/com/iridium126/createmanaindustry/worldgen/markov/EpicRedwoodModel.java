package com.iridium126.createmanaindustry.worldgen.markov;

import java.io.InputStream;
import java.util.Map;
import java.util.function.BiConsumer;
import org.w3c.dom.Element;

/** Executes the unchanged packaged EpicRedwood3072 XML with specialized sparse map stages. */
public final class EpicRedwoodModel {
    public static final String VALUES = "BDNnGEgJVM";
    public static final int WIDTH = 648, HEIGHT = 3072;
    private final MarkovModel structure;

    public EpicRedwoodModel(InputStream xml) {
        int[] maps = {0};
        structure = MarkovModel.load(xml, 81, 81, 192, Map.of("map", (map, compiler) -> {
            if (++maps[0] != 1 || !compiler.values.equals("BXTADNnWGEgJVvRYUCIOPQ")
                    || map.getParentNode() != map.getOwnerDocument().getDocumentElement())
                throw new IllegalArgumentException("EpicRedwood requires one terminal root map");
            for (var node = map.getNextSibling(); node != null; node = node.getNextSibling())
                if (node instanceof Element) throw new IllegalArgumentException("EpicRedwood map must be the last root operation");
            validateMap(map, 1);
            // Mapping is run after the structural program, retaining the exact random stream.
            return execution -> {};
        }));
        if (maps[0] != 1) throw new IllegalArgumentException("Missing EpicRedwood maps");
    }

    public RedwoodVolume generate(int seed) { return generate(seed, (name, volume) -> {}); }

    /** Observer exists for independent stage-by-stage reference validation. */
    public RedwoodVolume generate(int seed, BiConsumer<String, RedwoodVolume> observer) {
        var run = structure.generateExecution(seed, 10000);
        for (byte value : run.state) if (VALUES.indexOf(structure.values().charAt(value)) < 0)
            throw new IllegalStateException("EpicRedwood structure left an unmapped symbol");
        RedwoodVolume base = RedwoodVolume.from(run.state, 81, 81, 192);
        observer.accept("base", base);
        RedwoodVolume first = RedwoodRefinement.apply(base, structure.values(), 1, run.random.nextInt());
        observer.accept("stage1", first);
        RedwoodVolume result = RedwoodRefinement.apply(first, VALUES, 2, run.random.nextInt());
        observer.accept("stage2", result);
        return result;
    }

    private static void validateMap(Element map, int stage) {
        if (!map.getAttribute("scale").equals(stage == 1 ? "2 2 4" : "4 4 4")
                || !map.getAttribute("values").equals(VALUES) || !map.getAttribute("symmetry").equals("()")
                || !map.getAttribute("redwoodDetail").equals(Integer.toString(stage)))
            throw new IllegalArgumentException("Unsupported EpicRedwood map configuration");
        for (int i = 0; i < map.getAttributes().getLength(); i++)
            if (!java.util.List.of("scale", "values", "symmetry", "redwoodDetail").contains(map.getAttributes().item(i).getNodeName()))
                throw new IllegalArgumentException("Unsupported EpicRedwood map attribute");
        StringBuilder seen = new StringBuilder();
        int maps = 0;
        for (var node = map.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element child)) continue;
            if (child.getTagName().equals("map") && stage == 1) { validateMap(child, 2); maps++; continue; }
            if (!child.getTagName().equals("rule") || child.getAttributes().getLength() != 2)
                throw new IllegalArgumentException("Unsupported EpicRedwood map child");
            String in = child.getAttribute("in");
            if (in.length() != 1 || "DNnGEgJV".indexOf(in.charAt(0)) < 0 || seen.indexOf(in) >= 0)
                throw new IllegalArgumentException("Unsupported EpicRedwood mapping input");
            int side = stage == 1 ? 2 : 4;
            String row = in.repeat(side), layer = String.join("/", java.util.Collections.nCopies(side, row));
            if (!child.getAttribute("out").equals(String.join(" ", java.util.Collections.nCopies(4, layer))))
                throw new IllegalArgumentException("EpicRedwood sparse map requires uniform replication");
            seen.append(in);
        }
        if (seen.length() != 8 || maps != (stage == 1 ? 1 : 0))
            throw new IllegalArgumentException("Incomplete EpicRedwood map");
    }
}
