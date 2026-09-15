package com.iridium126.createmanaindustry.worldgen.markov;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/**
 * Immutable compiled MarkovJunior program. Coordinates follow upstream: X/Y horizontal, Z up.
 * Port of Maxim Gumin's MIT-licensed algorithms; see META-INF/licenses/MarkovJunior.txt.
 * Each invocation owns its mutable state, so a compiled model is safe across worldgen workers.
 */
public final class MarkovModel {
    @FunctionalInterface
    public interface Operation { void execute(Execution execution); }
    @FunctionalInterface
    public interface NodeCompiler { Operation compile(Element element, Compiler compiler); }

    private final Compiler compiler;
    private final Operation program;
    private final boolean origin;

    private MarkovModel(Compiler compiler, Operation program, boolean origin) {
        this.compiler = compiler;
        this.program = program;
        this.origin = origin;
    }

    public static MarkovModel load(InputStream xml, int x, int y, int z) {
        return load(xml, x, y, z, Map.of());
    }

    /** Extensions compile unknown tags to immutable operations; no global mutable registry. */
    public static MarkovModel load(InputStream xml, int x, int y, int z, Map<String, NodeCompiler> extensions) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Element root = factory.newDocumentBuilder().parse(xml).getDocumentElement();
            Compiler compiler = new Compiler(root, x, y, z, extensions);
            return new MarkovModel(compiler, compiler.compile(root), bool(root, "origin", false));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid MarkovJunior model: " + e.getMessage(), e);
        }
    }

    public byte[] generate(int seed, int maxSteps) {
        Execution execution = new Execution(compiler, seed, maxSteps);
        if (origin) execution.state[compiler.x / 2 + compiler.y / 2 * compiler.x
                + compiler.z / 2 * compiler.x * compiler.y] = 1;
        program.execute(execution);
        return execution.state;
    }

    public String values() { return compiler.values; }
    public int sizeX() { return compiler.x; }
    public int sizeY() { return compiler.y; }
    public int sizeZ() { return compiler.z; }

    /** Mutable invocation context, also available to custom node implementations. */
    public static final class Execution {
        public final byte[] state;
        public final DotNetRandom random;
        public final int sizeX, sizeY, sizeZ;
        private int remaining;
        private final byte[] buffer;
        private final boolean[] occupied;
        private final IntList changes = new IntList();

        private Execution(Compiler c, int seed, int budget) {
            if (budget < 1) throw new IllegalArgumentException("maxSteps must be positive");
            sizeX = c.x; sizeY = c.y; sizeZ = c.z;
            state = new byte[c.volume];
            buffer = new byte[c.volume];
            occupied = new boolean[c.volume];
            random = new DotNetRandom(seed);
            remaining = budget;
        }

        /** Call once per attempted rewrite round. Exhaustion never returns a partial tree. */
        public void step() {
            if (remaining-- <= 0) throw new IllegalStateException("MarkovJunior execution step budget exceeded");
        }
    }

    public static final class Compiler {
        public final int x, y, z, volume;
        public final String values;
        private final Map<Character, Integer> waves = new HashMap<>();
        private final Map<String, NodeCompiler> extensions;

        private Compiler(Element root, int x, int y, int z, Map<String, NodeCompiler> extensions) {
            if (x < 1 || y < 1 || z < 1 || (long)x * y * z > 1_048_576)
                throw new IllegalArgumentException("Invalid grid dimensions");
            this.x = x; this.y = y; this.z = z; volume = x * y * z;
            this.extensions = Map.copyOf(extensions);
            values = required(root, "values").replace(" ", "");
            if (values.isEmpty() || values.length() > 30) throw new IllegalArgumentException("Expected 1..30 values");
            for (int i = 0; i < values.length(); i++) {
                if (values.charAt(i) == '*' || waves.put(values.charAt(i), 1 << i) != null)
                    throw new IllegalArgumentException("Duplicate/reserved value");
            }
            waves.put('*', (1 << values.length()) - 1);
            if (bool(root, "origin", false) && values.length() < 2)
                throw new IllegalArgumentException("origin needs at least two values");
            // This profile deliberately rejects other symmetries instead of silently changing a model.
            if (!"()".equals(root.getAttribute("symmetry")))
                throw new IllegalArgumentException("This profile requires explicit symmetry=\"()\"");
        }

        public int value(char symbol) {
            int index = values.indexOf(symbol);
            if (index < 0) throw new IllegalArgumentException("Unknown value: " + symbol);
            return index;
        }

        public Operation compile(Element e) {
            if (e.hasAttribute("symmetry") && !e.getAttribute("symmetry").equals("()"))
                throw new IllegalArgumentException("Unsupported symmetry: " + e.getAttribute("symmetry"));
            return switch (e.getTagName()) {
                case "sequence" -> {
                    checkAttributes(e, "values", "origin", "symmetry");
                    Operation[] children = children(e).stream().map(this::compile).toArray(Operation[]::new);
                    yield execution -> { for (Operation child : children) child.execute(execution); };
                }
                case "one", "all", "prl" -> compileRules(e);
                case "convolution" -> compileConvolution(e);
                default -> {
                    NodeCompiler extension = extensions.get(e.getTagName());
                    if (extension == null) throw new IllegalArgumentException("Unsupported node: " + e.getTagName());
                    yield extension.compile(e, this);
                }
            };
        }

        private Operation compileRules(Element e) {
            checkAttributes(e, "in", "out", "p", "steps", "symmetry");
            List<Element> elements = children(e);
            if (elements.isEmpty()) elements = List.of(e);
            Rule[] rules = new Rule[elements.size()];
            for (int i = 0; i < rules.length; i++) {
                Element r = elements.get(i);
                if (r != e && !r.getTagName().equals("rule"))
                    throw new IllegalArgumentException("Unsupported rule child: " + r.getTagName());
                checkAttributes(r, "in", "out", "p", "symmetry", r == e ? "steps" : "");
                if (r.hasAttribute("symmetry") && !r.getAttribute("symmetry").equals("()"))
                    throw new IllegalArgumentException("Unsupported rule symmetry");
                rules[i] = new Rule(r, this);
            }
            return new Rewrite(e.getTagName(), rules, integer(e, "steps", 0), this);
        }

        private Operation compileConvolution(Element e) {
            checkAttributes(e, "in", "out", "p", "steps", "neighborhood", "periodic", "values", "sum");
            if (!required(e, "neighborhood").equals("VonNeumann") || bool(e, "periodic", false))
                throw new IllegalArgumentException("Supported convolution: nonperiodic VonNeumann");
            List<Element> elements = children(e);
            if (elements.isEmpty()) elements = List.of(e);
            ConvRule[] rules = new ConvRule[elements.size()];
            for (int i = 0; i < rules.length; i++) {
                Element r = elements.get(i);
                if (r != e) {
                    if (!r.getTagName().equals("rule")) throw new IllegalArgumentException("Expected convolution rule");
                    checkAttributes(r, "in", "out", "p", "values", "sum");
                }
                int mask = 0;
                if (r.hasAttribute("values") != r.hasAttribute("sum"))
                    throw new IllegalArgumentException("Convolution values and sum must be paired");
                for (char c : r.getAttribute("values").toCharArray()) mask |= 1 << value(c);
                boolean[] sums = new boolean[7];
                if (r.hasAttribute("sum")) for (String interval : r.getAttribute("sum").split(",")) {
                    String[] bounds = interval.split("\\.\\.");
                    int min = Integer.parseInt(bounds[0]), max = Integer.parseInt(bounds[bounds.length - 1]);
                    if (bounds.length > 2 || min < 0 || max > 6 || max < min) throw new IllegalArgumentException("Invalid sum");
                    Arrays.fill(sums, min, max + 1, true);
                } else Arrays.fill(sums, true);
                rules[i] = new ConvRule(value(single(r, "in")), (byte)value(single(r, "out")), mask, sums, probability(r));
            }
            int steps = integer(e, "steps", 0);
            return execution -> {
                for (int round = 0; steps <= 0 || round < steps; round++) {
                    execution.step();
                    // One byte per cell and rule, rather than upstream's cell x palette x int sumfield.
                    byte[][] counts = new byte[rules.length][volume];
                    for (int r = 0; r < rules.length; r++) {
                        if (rules[r].mask == 0) continue;
                        byte[] count = counts[r];
                        for (int zz = 0, i = 0; zz < z; zz++) for (int yy = 0; yy < y; yy++) for (int xx = 0; xx < x; xx++, i++) {
                            if ((rules[r].mask & (1 << execution.state[i])) == 0) continue;
                            if (xx > 0) count[i - 1]++;
                            if (xx + 1 < x) count[i + 1]++;
                            if (yy > 0) count[i - x]++;
                            if (yy + 1 < y) count[i + x]++;
                            if (zz > 0) count[i - x * y]++;
                            if (zz + 1 < z) count[i + x * y]++;
                        }
                    }
                    boolean changed = false;
                    for (int i = 0; i < volume; i++) for (int r = 0; r < rules.length; r++) {
                        ConvRule rule = rules[r];
                        if (execution.state[i] == rule.input && rule.output != execution.state[i]
                                && (rule.p == 1 || execution.random.nextInt() < rule.p * Integer.MAX_VALUE)
                                && rule.sums[counts[r][i]]) {
                            execution.state[i] = rule.output;
                            changed = true;
                            break;
                        }
                    }
                    if (!changed) break;
                }
            };
        }
    }

    private record ConvRule(int input, byte output, int mask, boolean[] sums, double p) {}

    private static final class Rule {
        final int x, y, z;
        final int[] input, offsets, writeOffsets;
        final byte[] output;
        final int[][] shifts;
        final double p;

        Rule(Element e, Compiler c) {
            Pattern in = Pattern.parse(required(e, "in")), out = Pattern.parse(required(e, "out"));
            x = in.x; y = in.y; z = in.z;
            if (x != out.x || y != out.y || z != out.z || x > c.x || y > c.y || z > c.z)
                throw new IllegalArgumentException("Rule dimensions must agree and fit the grid");
            p = probability(e);
            input = new int[in.data.length];
            offsets = new int[input.length];
            IntList writes = new IntList(), outputs = new IntList();
            IntList[] positions = new IntList[c.values.length()];
            for (int v = 0; v < positions.length; v++) positions[v] = new IntList();
            for (int i = 0; i < input.length; i++) {
                Integer mask = c.waves.get(in.data[i]);
                if (mask == null) throw new IllegalArgumentException("Unknown input: " + in.data[i]);
                input[i] = mask;
                offsets[i] = i % x + i / x % y * c.x + i / (x * y) * c.x * c.y;
                for (int v = 0; v < positions.length; v++) if ((mask & (1 << v)) != 0) positions[v].add(i);
                if (out.data[i] != '*') {
                    writes.add(offsets[i]);
                    outputs.add(c.value(out.data[i]));
                }
            }
            writeOffsets = writes.array();
            output = new byte[outputs.size];
            for (int i = 0; i < output.length; i++) output[i] = (byte)outputs.data[i];
            shifts = new int[positions.length][];
            for (int v = 0; v < positions.length; v++) shifts[v] = positions[v].array();
        }

        boolean matches(byte[] state, int at) {
            for (int i = 0; i < input.length; i++) if ((input[i] & (1 << state[at + offsets[i]])) == 0) return false;
            return true;
        }
    }

    private static final class Rewrite implements Operation {
        final String kind;
        final Rule[] rules;
        final int steps, x, y, z, volume;

        Rewrite(String kind, Rule[] rules, int steps, Compiler c) {
            this.kind = kind; this.rules = rules; this.steps = steps;
            x = c.x; y = c.y; z = c.z; volume = c.volume;
        }

        @Override public void execute(Execution e) {
            boolean parallel = kind.equals("prl"), one = kind.equals("one");
            IntList matches = new IntList();
            boolean[][] mask = parallel ? null : new boolean[rules.length][volume];
            e.changes.size = 0;
            for (int round = 0; steps <= 0 || round < steps; round++) {
                e.step();
                int previousEnd = e.changes.size;
                if (round == 0 || parallel) {
                    matches.size = 0;
                    for (int r = 0; r < rules.length; r++) {
                        Rule rule = rules[r];
                        // Upstream's tiled anchor scan: preserves match ordering and random draws.
                        for (int zz = rule.z - 1; zz < z; zz += rule.z)
                            for (int yy = rule.y - 1; yy < y; yy += rule.y)
                                for (int xx = rule.x - 1; xx < x; xx += rule.x)
                                    discover(e, rule, r, xx, yy, zz, mask, matches, parallel);
                    }
                } else {
                    for (int i = 0; i < previousEnd; i++) {
                        int at = e.changes.data[i];
                        for (int r = 0; r < rules.length; r++)
                            discover(e, rules[r], r, at % x, at / x % y, at / (x * y), mask, matches, false);
                    }
                }
                if (parallel) {
                    for (int i = previousEnd; i < e.changes.size; i++) {
                        int at = e.changes.data[i];
                        e.state[at] = e.buffer[at];
                    }
                    e.changes.size = 0;
                    if (matches.size == 0) return;
                } else if (one) {
                    e.changes.size = 0;
                    boolean applied = false;
                    while (matches.size > 0) {
                        int selected = e.random.nextInt(matches.size);
                        int match = matches.data[selected];
                        matches.data[selected] = matches.data[--matches.size];
                        int r = match / volume, at = match % volume;
                        mask[r][at] = false;
                        Rule rule = rules[r];
                        if (!rule.matches(e.state, at)) continue;
                        for (int i = 0; i < rule.output.length; i++) {
                            int pos = at + rule.writeOffsets[i];
                            if (e.state[pos] != rule.output[i]) {
                                e.state[pos] = rule.output[i];
                                e.changes.add(pos);
                            }
                        }
                        applied = true;
                        break;
                    }
                    if (!applied) return;
                } else {
                    e.changes.size = 0;
                    if (matches.size == 0) return;
                    int[] shuffle = new int[matches.size];
                    for (int i = 0; i < shuffle.length; i++) {
                        int j = e.random.nextInt(i + 1);
                        shuffle[i] = shuffle[j]; shuffle[j] = i;
                    }
                    for (int index : shuffle) {
                        int match = matches.data[index], r = match / volume, at = match % volume;
                        mask[r][at] = false;
                        Rule rule = rules[r];
                        boolean fits = true;
                        for (int offset : rule.writeOffsets) if (e.occupied[at + offset]) { fits = false; break; }
                        if (!fits) continue;
                        for (int i = 0; i < rule.output.length; i++) {
                            int pos = at + rule.writeOffsets[i];
                            e.occupied[pos] = true;
                            e.state[pos] = rule.output[i];
                            e.changes.add(pos);
                        }
                    }
                    for (int i = 0; i < e.changes.size; i++) e.occupied[e.changes.data[i]] = false;
                    matches.size = 0;
                }
            }
        }

        private void discover(Execution e, Rule rule, int r, int xx, int yy, int zz,
                              boolean[][] mask, IntList matches, boolean parallel) {
            int anchor = xx + yy * x + zz * x * y;
            for (int shift : rule.shifts[e.state[anchor]]) {
                int sx = xx - shift % rule.x, sy = yy - shift / rule.x % rule.y, sz = zz - shift / (rule.x * rule.y);
                if (sx < 0 || sy < 0 || sz < 0 || sx + rule.x > x || sy + rule.y > y || sz + rule.z > z) continue;
                int at = sx + sy * x + sz * x * y;
                if ((!parallel && mask[r][at]) || !rule.matches(e.state, at)) continue;
                if (parallel) {
                    if (e.random.nextDouble() > rule.p) continue;
                    for (int i = 0; i < rule.output.length; i++) {
                        int pos = at + rule.writeOffsets[i];
                        if (rule.output[i] != e.state[pos]) {
                            e.buffer[pos] = rule.output[i];
                            e.changes.add(pos);
                        }
                    }
                } else mask[r][at] = true;
                matches.add(r * volume + at);
            }
        }
    }

    private record Pattern(int x, int y, int z, char[] data) {
        static Pattern parse(String text) {
            String[] layers = text.split(" ", -1);
            String[] first = layers[0].split("/", -1);
            int x = first[0].length(), y = first.length, z = layers.length;
            if (x == 0) throw new IllegalArgumentException("Empty pattern");
            char[] data = new char[x * y * z];
            for (int zz = 0; zz < z; zz++) {
                String[] rows = layers[z - 1 - zz].split("/", -1);
                if (rows.length != y) throw new IllegalArgumentException("Nonrectangular pattern");
                for (int yy = 0; yy < y; yy++) {
                    if (rows[yy].length() != x) throw new IllegalArgumentException("Nonrectangular pattern");
                    rows[yy].getChars(0, x, data, yy * x + zz * x * y);
                }
            }
            return new Pattern(x, y, z, data);
        }
    }

    private static final class IntList {
        int[] data = new int[32];
        int size;
        void add(int value) {
            if (size == data.length) data = Arrays.copyOf(data, size * 2);
            data[size++] = value;
        }
        int[] array() { return Arrays.copyOf(data, size); }
    }

    private static List<Element> children(Element e) {
        List<Element> result = new ArrayList<>();
        for (var child = e.getFirstChild(); child != null; child = child.getNextSibling())
            if (child instanceof Element element) result.add(element);
        return result;
    }
    private static String required(Element e, String name) {
        if (!e.hasAttribute(name)) throw new IllegalArgumentException("Missing " + name + " in " + e.getTagName());
        return e.getAttribute(name);
    }
    private static char single(Element e, String name) {
        String text = required(e, name);
        if (text.length() != 1) throw new IllegalArgumentException("Expected a single symbol for " + name);
        return text.charAt(0);
    }
    private static int integer(Element e, String name, int fallback) {
        return e.hasAttribute(name) ? Integer.parseInt(e.getAttribute(name)) : fallback;
    }
    private static boolean bool(Element e, String name, boolean fallback) {
        if (!e.hasAttribute(name)) return fallback;
        String value = e.getAttribute(name);
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false"))
            throw new IllegalArgumentException("Invalid boolean " + name);
        return Boolean.parseBoolean(value);
    }
    private static double probability(Element e) {
        double p = e.hasAttribute("p") ? Double.parseDouble(e.getAttribute("p")) : 1;
        if (!Double.isFinite(p) || p < 0 || p > 1) throw new IllegalArgumentException("p must be in [0,1]");
        return p;
    }
    private static void checkAttributes(Element e, String... allowed) {
        List<String> names = Arrays.asList(allowed);
        for (int i = 0; i < e.getAttributes().getLength(); i++) {
            String name = e.getAttributes().item(i).getNodeName();
            if (!names.contains(name)) throw new IllegalArgumentException("Unsupported attribute " + name + " in " + e.getTagName());
        }
    }
}
