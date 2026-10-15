```json
{
  "title": "Partition",
  "icon": "hexcasting:battery",
  "ordinal": 14,
  "fabric:load_conditions": {
    "condition": "fabric:all_mods_loaded",
    "values": [
      "hexcasting",
      "trickster"
    ]
  },
  "category": "createmanaindustry:mana_industry",
  "additional_search_terms": [
    "Partition",
    "分域",
    "Anima"
  ]
}
```

A [Great Trick](^createmanaindustry:mana_industry/great_tricks) taking **Vector, Vector → Boolean**. Both vectors are rounded down to block positions. Their inclusive cuboid costs **1 Anima per block**: a 3 × 3 × 3 volume costs **27 Anima**.

;;;;;

<|great-trick@createmanaindustry:book_components|trick-id=createmanaindustry:partition|>

;;;;;

<|page-title@lavender:book_components|title=Inside and Outside|>

The cuboid's six outer faces separate space. Casting between its inside and outside requires infinite distance. Ordinary finite ambits and sentinel extensions cannot cross. Two points both outside retain their ordinary distance, even if the line between them passes through the cuboid. Nested partitions each contribute their own distinction.

Configured player true-name exemptions still apply; [Ectoplasmic Inversion](^createmanaindustry:mana_industry/ectoplasmic_inversion) still complements the resulting ambit. Distance-priced tricks fail across a partition before spending anything; exchanging resources cannot pay an infinite cost. Pure distance calculations and operations with no distance restriction or price remain unchanged.

;;;;;

<|page-title@lavender:book_components|title=Drawing a Boundary|>

Both endpoint block centers must lie in this chain's spatial ambit. Boundaries belonging to different chains may nest strictly but cannot intersect or even touch. Each chain holds one partition. A successful replacement pays its full new volume and replaces the old boundary immediately; the old boundary is ignored during validation.

Success returns true. Invalid input, coordinates, range, conflicting boundaries or insufficient Anima return false without payment or replacing the old domain. Moving away or unloading its chunks does not end a partition.

;;;;;

<|page-title@lavender:book_components|title=An Unseen Wall|>

Ordinary entities, projectiles and vehicles collide with its faces like block surfaces. Same-dimension teleports cannot change partitions; outside-to-outside teleports remain possible. Spectators, no-physics entities and interdimensional travel retain their usual behavior.

An entity cut by a newly created boundary may clear it before becoming constrained by its current side. Merely touching a face grants no new exemption. The server's entity-ID movement exemption list defaults to Create contraptions, including trains. Their passengers travel with them; spell range and costs are still checked.

;;;;;

<|page-title@lavender:book_components|title=Holding the Domain|>

Pale blue edges and translucent particle membranes mark the boundary for nearby observers. It ends with its casting chain, cancellation, or its caster's death or logout. The wall disappears immediately, followed by a purely visual dissolution.

Running this trick alone in a mirror ends its domain as soon as execution finishes. Keep the chain running or delayed, or retain it in a staff session. Saved long spells restore their partition without charging again. A conflicting saved partition is dropped with a message, while the rest of the spell continues.
