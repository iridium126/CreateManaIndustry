package com.iridium126.createmanaindustry.compat.hextrick.data;

import at.petrak.hexcasting.api.casting.iota.EntityIota;
import at.petrak.hexcasting.api.casting.iota.Iota;
import com.iridium126.createmanaindustry.compat.hextrick.conversion.FragmentConverter;
import dev.enjarai.trickster.spell.Fragment;
import dev.enjarai.trickster.spell.SpellPart;
import dev.enjarai.trickster.spell.fragment.EntityFragment;
import dev.enjarai.trickster.spell.fragment.ListFragment;
import dev.enjarai.trickster.spell.fragment.MapFragment;
import io.vavr.collection.HashMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/** Registration-time structural contract. Unknown fragment classes remain opaque leaves. */
public final class FragmentStructures {
    public interface Adapter<F extends Fragment> {
        List<Fragment> children(F fragment);
        F rebuild(F original, List<Fragment> children);
        default long units(F fragment, boolean glyph) { return Math.max(1L, (fragment.getWeight() + 7L) / 8L) - (glyph ? 1 : 0); }
        default List<Iota> references(F fragment) { return List.of(); }
        default Fragment visitReferences(F fragment, UnaryOperator<Iota> visitor) { return fragment; }
    }

    private static final Map<Class<?>, Adapter<?>> ADAPTERS = new LinkedHashMap<>();
    private static boolean frozen;

    static {
        register(SpellPart.class, new Adapter<>() {
            public long units(SpellPart fragment, boolean glyph) { return 1; }
            public List<Fragment> children(SpellPart part) {
                var children = new ArrayList<Fragment>(part.subParts.size() + 1);
                children.add(part.glyph);
                children.addAll(part.subParts);
                return children;
            }
            public SpellPart rebuild(SpellPart part, List<Fragment> children) {
                return new SpellPart(children.getFirst(), children.subList(1, children.size()).stream()
                        .map(child -> (SpellPart) child).toList());
            }
        });
        register(ListFragment.class, new Adapter<>() {
            public long units(ListFragment fragment, boolean glyph) { return 1; }
            public List<Fragment> children(ListFragment list) { return list.fragments(); }
            public ListFragment rebuild(ListFragment list, List<Fragment> children) { return new ListFragment(children); }
        });
        register(MapFragment.class, new Adapter<>() {
            public long units(MapFragment fragment, boolean glyph) { return 1; }
            public List<Fragment> children(MapFragment map) {
                var children = new ArrayList<Fragment>(map.map().size() * 2);
                map.map().forEach(pair -> { children.add(pair._1()); children.add(pair._2()); });
                return children;
            }
            public MapFragment rebuild(MapFragment map, List<Fragment> children) {
                HashMap<Fragment, Fragment> values = HashMap.empty();
                for (int i = 0; i < children.size(); i += 2) values = values.put(children.get(i), children.get(i + 1));
                return new MapFragment(values);
            }
        });
        register(IotaFragment.class, new Adapter<>() {
            public long units(IotaFragment fragment, boolean glyph) { return 1L + fragment.getIota().size(); }
            public List<Fragment> children(IotaFragment fragment) { return List.of(); }
            public IotaFragment rebuild(IotaFragment original, List<Fragment> children) { return original; }
            public List<Iota> references(IotaFragment fragment) { return List.of(fragment.getIota()); }
            public Fragment visitReferences(IotaFragment fragment, UnaryOperator<Iota> visitor) {
                Iota before = fragment.getIota();
                Iota after = before.visit(visitor);
                return before == after ? fragment : new IotaFragment(IotaFragment.serializeIota(after));
            }
        });
        register(EntityFragment.class, new Adapter<>() {
            public List<Fragment> children(EntityFragment fragment) { return List.of(); }
            public EntityFragment rebuild(EntityFragment original, List<Fragment> children) { return original; }
            public List<Iota> references(EntityFragment fragment) {
                return List.of(new EntityIota(fragment.uuid(), fragment.name().copy()));
            }
            public Fragment visitReferences(EntityFragment fragment, UnaryOperator<Iota> visitor) {
                Iota before = references(fragment).getFirst();
                Iota after = before.visit(visitor);
                if (before == after) return fragment;
                return FragmentConverter.iotaToFragment(after);
            }
        });
    }

    private FragmentStructures() {}
    public static synchronized <F extends Fragment> void register(Class<F> type, Adapter<F> adapter) {
        if (frozen) throw new IllegalStateException("Fragment structure registration is closed");
        if (ADAPTERS.putIfAbsent(type, adapter) != null) throw new IllegalArgumentException("Duplicate fragment adapter: " + type.getName());
    }
    public static synchronized void freeze() { frozen = true; }
    @SuppressWarnings("unchecked")
    public static Adapter<Fragment> adapter(Fragment fragment) { return (Adapter<Fragment>) ADAPTERS.get(fragment.getClass()); }
    public static List<Fragment> children(Fragment fragment) {
        var adapter = adapter(fragment);
        return adapter == null ? List.of() : adapter.children(fragment);
    }
    public static List<Iota> references(Fragment fragment) {
        var adapter = adapter(fragment);
        return adapter == null ? List.of() : adapter.references(fragment);
    }

    /** Preflight before native recursive encoding: bound known graphs and reject cycles. */
    public static void validate(Fragment root) {
        record Entry(Fragment value, int depth, boolean exit) {}
        var pending = new ArrayDeque<Entry>();
        var path = new IdentityHashMap<Fragment, Boolean>();
        pending.push(new Entry(root, 1, false));
        int nodes = 0;
        while (!pending.isEmpty()) {
            Entry entry = pending.pop();
            if (entry.exit()) { path.remove(entry.value()); continue; }
            if (++nodes > DecodeBudget.MAX_NODES || entry.depth() > DecodeBudget.MAX_DEPTH)
                throw new DecodeBudget.DecodeFailure("fragment_graph_limit");
            List<Fragment> children = children(entry.value());
            if (children.isEmpty()) continue;
            if (path.put(entry.value(), true) != null) throw new DecodeBudget.DecodeFailure("fragment_cycle");
            pending.push(new Entry(entry.value(), entry.depth(), true));
            for (Fragment child : children) pending.push(new Entry(child, entry.depth() + 1, false));
        }
    }

    public static Fragment visit(Fragment root, UnaryOperator<Iota> visitor) {
        var adapter = adapter(root);
        if (adapter == null) return root;
        List<Fragment> before = adapter.children(root);
        var after = new ArrayList<Fragment>(before.size());
        boolean changed = false;
        for (Fragment child : before) {
            Fragment value = visit(child, visitor);
            after.add(value);
            changed |= value != child;
        }
        Fragment result = changed ? adapter.rebuild(root, after) : root;
        return adapter.visitReferences(result, visitor);
    }

    public static void validateDecoded(Fragment root, DecodeBudget budget) {
        record Entry(Fragment fragment, boolean exit) {}
        var pending = new ArrayDeque<Entry>();
        pending.push(new Entry(root, false));
        int entered = 0;
        try {
            while (!pending.isEmpty()) {
                var entry = pending.pop();
                if (entry.exit()) { budget.pop(); entered--; continue; }
                budget.push();
                entered++;
                references(entry.fragment()); // Nested iota codecs inherit this graph depth and byte allowance.
                budget.check();
                List<Fragment> children = children(entry.fragment());
                if (children.isEmpty()) { budget.pop(); entered--; }
                else {
                    pending.push(new Entry(entry.fragment(), true));
                    for (Fragment child : children) pending.push(new Entry(child, false));
                }
            }
        } finally {
            while (entered-- > 0) budget.pop();
        }
    }
}
