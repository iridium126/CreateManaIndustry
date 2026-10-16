```json
{
  "title": "Shared Fate",
  "icon": "hexcasting:battery",
  "ordinal": 12,
  "fabric:load_conditions": {
    "condition": "fabric:all_mods_loaded",
    "values": ["hexcasting", "trickster"]
  },
  "additional_search_terms": ["同命", "Great Trick", "Anima", "Mishap"],
  "category": "createmanaindustry:mana_industry"
}
```

A [Great Trick](^createmanaindustry:mana_industry/great_tricks). Takes one Entity fragment and returns that same fragment. Each successful binding costs **32 Anima**.

It binds your fate to the entity: whenever you suffer a Hex Casting mishap, the target suffers that same mishap, using its own health, items and other attributes. A mishap that halves your health also halves the target's current health.

;;;;;

<|great-trick@createmanaindustry:book_components|trick-id=createmanaindustry:shared_fate|>

;;;;;

<|page-title@lavender:book_components|title=Binding a Fate|>

The entity must exist in your dimension and lie inside your current Hex Casting spatial ambit, including sentinel extensions and this chain's [Ectoplasmic Inversion](^createmanaindustry:mana_industry/ectoplasmic_inversion). A player's true name grants no distance exemption here.

You cannot bind yourself, bind the same entity twice across your active chains, or close a directed cycle. Failed bindings spend no Anima. Other entities, including those without life, may be bound; attributes they lack are unaffected.

;;;;;

<|page-title@lavender:book_components|title=Following the Thread|>

You may bind several entities. Pale blue threads mark the bindings for observers in the same dimension. Each thread remains until its casting chain ends or the target leaves your ambit, dies, is removed or changes dimension.

A broken binding does not return when the target comes back. Long-running spells retain their targets when saved, and check them again when execution resumes without charging again.

Running Shared Fate alone from an evaluation mirror ends its chain as soon as it returns, releasing the binding. Keep that spell delayed or running to maintain the thread. When called through a TrickIota with a staff, the binding can remain in the staff session until its state is cleared or reset.

;;;;;

<|page-title@lavender:book_components|title=One Direction|>

Fate travels from caster to target. If A binds B and B binds C, A's mishap reaches B and C; B's mishap reaches C and never travels back to A. B therefore cannot bind A.

An entity receives each original mishap once, even when several paths reach it. The last mishap can still travel onward if it kills the caster. Recipients use their own casting state; inversion is not passed along the thread.

