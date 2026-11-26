```json
{
  "title": "Great Tricks",
  "icon": "trickster:mirror_of_evaluation",
  "ordinal": 9,
  "fabric:load_conditions": {
    "condition": "fabric:all_mods_loaded",
    "values": [
      "hexcasting",
      "trickster"
    ]
  },
  "additional_search_terms": [
    "卓越戏法",
    "great_trick",
    "Ectoplasmic Inversion",
    "Shared Fate",
    "Tincture Shaping",
    "Partition",
    "Anima"
  ],
  "category": "createmanaindustry:mana_industry"
}
```

Unlike ordinary tricks, Great Tricks use a hexagonal lattice with blue strokes. Hold a [Mirror of Evaluation](^trickster:items/writing_casting/mirror_of_evaluation) in the main hand and a Hex Casting staff in the offhand, then open the mirror to enter the Great Trick editor.

They pay in [Anima](^createmanaindustry:mana_industry/anima) and require a player caster.

;;;;;

<|page-title@lavender:book_components|title=Connecting and Retracing|>

Distant dots may be connected directly. Any intervening dots are connected in sequence. An edge cannot be used twice; retracing the most recently drawn line erases it.

The edges identify a trick, rather than the starting point or direction. Remove the offhand staff and reopen the mirror to return to ordinary trick drawing.

;;;;;

<|page-title@lavender:book_components|title=Part of a Spell Tree|>

Great Tricks and ordinary tricks can occupy the same spell tree. The Great Trick editor preserves ordinary nodes for display and execution. Return to the ordinary editor before redrawing their patterns.

A saved Great Trick does not require the two items to remain held, but still requires a player caster. This entry covers Ectoplasmic Inversion, Shared Fate, Tincture Shaping and Partition.

;;;;;

<|great-trick@createmanaindustry:book_components|trick-id=createmanaindustry:ectoplasmic_inversion|>

Returns the state after the toggle: {#33ab89}True{} when enabled, {#aa3355}False{} when disabled.

Within one casting chain, cast once to enable inversion, then cast again to disable it. Disabling also pays soul; an insufficient balance leaves the switch unchanged.

;;;;;

<|page-title@lavender:book_components|title=Note: Inverting Reach|>

Hex Casting's ordinary spatial ambit becomes its exterior, including sentinel extensions. Its ordinary true-name distance exemption remains effective.

Trickster's existing close-range reading, writing, channel and mana-converter checks are also inverted. Unrestricted tricks gain no new distance limit. World bounds, loaded chunks and editing permissions still apply.

;;;;;

<|page-title@lavender:book_components|title=Note: Exchanging Costs|>

Operations that normally spend media instead spend mana, and those spending mana instead spend media. The equivalent payment is **doubled**.

Equivalence compares only the server's media and mana content per bucket. It does not change actual fluid recipes.

By default, a bucket of Liquid Media represents 40 amethyst-dust equivalents, and a bucket of Liquid Mana holds 2,048G:

- A cost of 1 dust becomes 102.4G.
- A cost of 1,024G becomes 40 dust equivalents of media.

Media payments retain Hex Casting's ordinary overcasting conditions. Anima itself is not exchanged.

;;;;;

<|page-title@lavender:book_components|title=Note: True Names and Chains|>

While inversion is active, true-name scans from any player or casting environment skip the caster's true name, including playerless circles and scans from another dimension.

Other unprotected true names can still cause a mishap. Skipping the caster's name does not exempt the whole List or remove other requirements for the operation.

Inversion follows sub-calls and long-running continuations of the same chain; independent casts neither exchange costs nor invert reach. The exemption ends when every branch finishes, is cancelled, or the caster disconnects. If another inverted chain belonging to the same player remains, the exemption persists until the last one is disabled or ends.

;;;;;

<|great-trick@createmanaindustry:book_components|trick-id=createmanaindustry:shared_fate|>

Takes one Entity fragment and returns its input.

It binds the caster's fate to the target: whenever the caster suffers a Hex Casting mishap, the target suffers that same mishap. The mishap uses the target's own health, items and other attributes; one that halves the caster's health also halves the target's current health.

;;;;;

<|page-title@lavender:book_components|title=Note: Binding a Fate|>

The target must exist in the caster's dimension and lie inside their current Hex Casting spatial ambit, including sentinel extensions and this chain's Ectoplasmic Inversion. A player's true-name distance exemption does not apply here.

The caster cannot bind themselves, bind the same entity twice across their active chains, or close a directed cycle. A failed binding spends no Anima. Entities without life may be bound; attributes they lack are unaffected.

;;;;;

<|page-title@lavender:book_components|title=Note: Following the Thread|>

A caster may bind several entities. Pale blue threads mark the bindings for observers in the same dimension. A binding ends with its casting chain, or when the target leaves the ambit, dies, is removed or changes dimension.

A broken binding does not return when the target comes back. Long-running spells retain their targets when saved and check them again on resuming, without charging again.

Running Shared Fate alone from an evaluation mirror ends its chain as soon as it returns, releasing the binding. Keep that spell delayed or running to maintain the thread; when called through a TrickIota with a staff, the binding can remain in the staff session until its state is cleared or reset.

;;;;;

<|page-title@lavender:book_components|title=Note: One Direction|>

Fate travels from caster to target. If A binds B and B binds C, A's mishap reaches B and C; B's mishap reaches C and never travels back to A. B therefore cannot bind A.

An entity receives each original mishap once, even when several paths reach it. The last mishap can still travel onward if it kills the caster. Recipients use their own casting state; inversion is not passed along the thread.

;;;;;

<|great-trick@createmanaindustry:book_components|trick-id=createmanaindustry:tincture_shaping|>

Takes an Entity fragment and a [Potion fragment](^createmanaindustry:mana_industry/potion_fragments), in that order, and returns its input.

It applies a fresh copy of the complete stored effect to a living creature. The caster may target themselves.

;;;;;

<|page-title@lavender:book_components|title=Note: Reach and True Names|>

The target must resolve in the current dimension and be in an entity-ticking chunk. Hex Casting's entity ambit applies, including sentinel extensions and this chain's Ectoplasmic Inversion.

Its configured player true-name distance exemption also applies: an exempt player target can be reached beyond ordinary spatial limits. Other targets retain the spatial range check.

;;;;;

<|page-title@lavender:book_components|title=Note: Merging the Tincture|>

Existing effects merge by Minecraft's ordinary level, duration and display rules. Entity immunity and mod effect events remain effective. A change to hidden fallback effects also counts as success.

Instant effects are added as a stored status instance, as with /effect, preserving their duration. They may act repeatedly on effect ticks; this is not a single drinking-potion pulse.

;;;;;

<|page-title@lavender:book_components|title=Note: Paying in Soul|>

Insufficient Anima prevents application. Immunity, blocked application or an unchanged complete effect causes a blunder without payment. Successful applications can draw from several batteries.

The cost is unchanged by Ectoplasmic Inversion's exchanged and doubled media/mana costs.

;;;;;

<|great-trick@createmanaindustry:book_components|trick-id=createmanaindustry:partition|>

Takes two Vectors and returns {#33ab89}True{} or {#aa3355}False{}. Both vectors are rounded down to block positions; the two blocks become opposite corners of an inclusive cuboid.

Invalid arguments, coordinates, range, boundaries or balance return {#aa3355}False{} without spending Anima or replacing the old domain.

;;;;;

<|page-title@lavender:book_components|title=Note: Inside and Outside|>

The cuboid's six outer faces separate space. Casting between its inside and outside requires infinite distance. Ordinary finite ambits and sentinel extensions cannot cross. Two points both outside retain their ordinary distance, even if the line between them passes through the cuboid. Nested partitions each contribute their own distinction.

Configured player true-name exemptions still apply; Ectoplasmic Inversion still complements the resulting ambit. Distance-priced tricks fail across a partition before spending anything; exchanging resources cannot pay an infinite cost. Pure distance calculations and operations with no distance restriction or price remain unchanged.

;;;;;

<|page-title@lavender:book_components|title=Note: Drawing a Boundary|>

Each enclosed block costs 1Anima; a 3 × 3 × 3 volume therefore costs 27Anima. Both endpoint block centers must lie in this chain's spatial ambit. Boundaries belonging to different chains may nest strictly but cannot intersect or even touch.

Each chain holds one partition. A replacement pays its full new volume and takes effect immediately; the old boundary is ignored during validation. Moving away or unloading its chunks does not end a partition.

;;;;;

<|page-title@lavender:book_components|title=Note: An Unseen Wall|>

Ordinary entities, projectiles and vehicles collide with its faces like block surfaces. Same-dimension teleports cannot cross a partition; outside-to-outside teleports remain possible. Spectators, no-physics entities and interdimensional travel retain their usual behavior.

An entity cut by a newly created boundary may clear it before becoming constrained by its current side. Merely touching a face grants no new exemption.

The server's entity-ID movement exemption list defaults to Create contraptions, including trains; their passengers travel with them. Spell range and costs are still checked.

;;;;;

<|page-title@lavender:book_components|title=Note: Holding the Domain|>

Pale blue edges and translucent particle membranes mark the boundary for nearby observers. It ends with its casting chain, cancellation, or its caster's death or logout; the wall disappears immediately, followed by a purely visual dissolution.

Running this trick alone in a mirror ends its domain as soon as execution finishes. Keep the chain running or delayed, or retain it in a staff session. Saved long spells restore their partition without charging again; a conflicting saved partition is dropped while the rest of the spell continues.
