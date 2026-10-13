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
    "great_trick"
  ],
  "category": "createmanaindustry:mana_industry"
}
```

Great Tricks use a different lattice. Hold a [Mirror of Evaluation](^trickster:items/writing_casting/mirror_of_evaluation) in the main hand and a Hex Casting staff in the offhand, then open the mirror. The dots form a hexagon, with blue strokes.

These tricks spend [Anima](^createmanaindustry:mana_industry/anima) and require a player caster.

;;;;;

<|page-title@lavender:book_components|title=Connecting and Retracing|>

Distant dots may be connected directly. Any intervening dots are connected in sequence. An edge cannot be used twice; retracing the most recently drawn line erases it.

The edges identify a trick, rather than the starting point or direction. Remove the offhand staff and reopen the mirror to return to ordinary trick drawing.

;;;;;

<|page-title@lavender:book_components|title=Part of a Spell Tree|>

Great Tricks and ordinary tricks can occupy the same spell tree. The Great Trick editor preserves ordinary nodes for display and execution. Return to the ordinary editor to redraw their patterns.

A saved Great Trick does not require the two items to remain held, but still requires a player caster. The first available Great Trick is [Ectoplasmic Inversion](^createmanaindustry:mana_industry/ectoplasmic_inversion).
