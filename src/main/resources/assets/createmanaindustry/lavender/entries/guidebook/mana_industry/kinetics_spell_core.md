```json
{
  "title": "Kinetics Spell Core",
  "icon": "createmanaindustry:kinetics_spell_core",
  "ordinal": 4,
  "associated_items": [
    "createmanaindustry:kinetics_spell_core"
  ],
  "fabric:load_conditions": {
    "condition": "fabric:all_mods_loaded",
    "values": [
      "trickster",
      "bits_n_bobs"
    ]
  },
  "category": "createmanaindustry:mana_industry"
}
```

The Kinetics Spell Core is a [Spell Core](^trickster:items/infrastructure/spell_core) driven by a **Bits 'n' Bobs cogwheel chain**. Inscribe the core, fit it into a [Modular Spell Construct](^trickster:items/infrastructure/modular_spell_construct), and connect the construct to the chain.

Without a connected, rotating chain, this core does not execute its spell.

;;;;;

<|page-title@lavender:book_components|title=Execution Speed|>

A chain speed of 32 gives the ordinary execution budget. Doubling that speed doubles the budget. This changes the number of circles that may execute per Tick, rather than the mana a spell requires.

Each of the four cores runs its own spell and draws from the construct's central Knot.

;;;;;

<|page-title@lavender:book_components|title=The Kinetic Load|>

Each Kinetics Spell Core adds **4 × absolute speed SU** to its chain controller's load. Several cores add their loads together, so provide enough stress capacity for the whole chain.

A [Kinetic Mana Generator](^createmanaindustry:mana_industry/charging_knots) can refill the central Knot, while a [Display Link](^createmanaindustry:mana_industry/display_link) supplies machine readings.
