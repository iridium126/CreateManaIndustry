package com.iridium126.createmanaindustry.compat.hextrick.data;

import at.petrak.hexcasting.api.casting.iota.EntityIota;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.common.lib.hex.HexIotaTypes;
import dev.enjarai.trickster.spell.Fragment;
import dev.enjarai.trickster.spell.SpellPart;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Owned, wire-compatible spell data for the dual-mod bridge. SpellPart.deepClone()
 * shares non-SpellPart glyphs, including mutable lists and vectors; crossing the
 * native serialization boundary also snapshots those and third-party fragments.
 * Only loaded when both Hexcasting and Trickster are present.
 */
public final class TrickSpellSnapshot {
    private final byte[] bytes;
    private final SpellPart spell;
    private final int size;
    private final int depth;
    private final List<Supplier<Iota>> references;
    private final int hash;

    public TrickSpellSnapshot(SpellPart source) {
        bytes = BridgeFragmentCodec.encode(source);
        spell = decode();
        var leaves = new ArrayList<Supplier<Iota>>();
        Metrics metrics = analyze(spell, leaves);
        size = metrics.size();
        depth = metrics.depth();
        references = List.copyOf(leaves);
        hash = spell.hashCode();
    }

    public SpellPart copySpell() {
        return decode();
    }

    public byte[] copyBytes() {
        return bytes.clone();
    }

    public int size() { return size; }
    public int depth() { return depth; }
    public boolean isEmpty() { return spell.isEmpty(); }

    /** Fresh values prevent callers from mutating the snapshot through an embedded iota. */
    public List<Iota> embeddedIotas() {
        return references.stream().map(Supplier::get).toList();
    }

    private SpellPart decode() {
        return BridgeFragmentCodec.decodeSpell(bytes);
    }

    private record Pending(Fragment fragment, int depth, boolean glyph) {}
    private record Metrics(int size, int depth) {}

    private static Metrics analyze(Fragment root, List<Supplier<Iota>> references) {
        var pending = new ArrayDeque<Pending>();
        pending.push(new Pending(root, 1, false));
        long size = 0;
        int depth = 1;
        while (!pending.isEmpty()) {
            Pending entry = pending.pop();
            Fragment fragment = entry.fragment();
            depth = Math.max(depth, entry.depth());
            var adapter = FragmentStructures.adapter(fragment);
            size += adapter == null ? Math.max(1L, (fragment.getWeight() + 7L) / 8L) - (entry.glyph() ? 1 : 0)
                    : adapter.units(fragment, entry.glyph());
            List<Fragment> children = FragmentStructures.children(fragment);
            for (int index = children.size() - 1; index >= 0; index--) {
                pending.push(new Pending(children.get(index), entry.depth() + 1, fragment instanceof SpellPart && index == 0));
            }
            for (Iota iota : FragmentStructures.references(fragment)) {
                depth = Math.max(depth, (int) Math.min(HexIotaTypes.MAX_SERIALIZATION_DEPTH,
                        (long) entry.depth() + iota.depth()));
                if (iota instanceof EntityIota entity) {
                    var id = entity.getEntityId();
                    var name = entity.getEntityName() == null ? null : entity.getEntityName().copy();
                    boolean player = entity.isPlayer();
                    references.add(() -> new EntityIota(id, name == null ? null : name.copy(), player));
                } else {
                    byte[] encoded = IotaFragment.serializeIota(iota);
                    references.add(() -> new IotaFragment(encoded).getIota());
                }
            }
            size = Math.min(size, HexIotaTypes.MAX_SERIALIZATION_TOTAL);
            depth = Math.min(depth, HexIotaTypes.MAX_SERIALIZATION_DEPTH);
        }
        return new Metrics((int) size, depth);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TrickSpellSnapshot snapshot && spell.equals(snapshot.spell);
    }

    @Override
    public int hashCode() {
        return hash;
    }
}
