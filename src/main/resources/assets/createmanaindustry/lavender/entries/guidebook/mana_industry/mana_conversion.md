```json
{
  "title": "Media Conversion",
  "icon": "hexcasting:battery",
  "category": "createmanaindustry:mana_industry",
  "ordinal": 5,
  "fabric:load_conditions": {
    "condition": "fabric:all_mods_loaded",
    "values": ["hexcasting", "trickster"]
  },
  "additional_search_terms": ["Ploy of the Transmuter", "Mana to Media"]
}
```

A ploy for exchanging traditional mana for the media used by Hex Casting.

;;;;;

<|trick@trickster:templates|trick-id=createmanaindustry:mana_to_media|>

Converts the given amount of mana from the caster's reserves into media.
When cast by a player, stores the media in rechargeable items in the player's
inventory, including armor and the offhand. Returns the media yield in units of 10000 media,
so a result of 1 means 10000 media.

The entire yield must fit. Missing storage or insufficient room causes a blunder;
no mana is consumed and no media is stored.

;;;;;

<|page-title@lavender:book_components|title=Casting from Constructs|>

A [Spell Construct](^trickster:items/infrastructure/spell_construct) draws the
mana from its inserted [Knot](^trickster:items/mana/knots) and stores the media
in its own reserve. This reserve also supplies Hexes cast through
[Eval Iota](^createmanaindustry:mana_industry/tricks).

[Modular Spell Constructs](^trickster:items/infrastructure/modular_spell_construct)
use the same reserve for all four cores. If the reserve is full or the knot
cannot supply the requested mana, the conversion fails without changing either
reserve.

;;;;;

<|page-title@lavender:book_components|title=Exchange Rate|>

For an amount **y** of mana, the media yield is `y / R × 10000`, where:

`R = 25.6 + 76.8 / (1 + exp(-0.1 × (mspt - 25)))`

Here, **mspt** is the execution time in milliseconds of the last completed server
tick, excluding the wait for the next tick. At 25 ms, an amount of 64 mana
produces 10000 media and returns 1.

Amounts must be finite and positive. Mana is rounded down to thousandths and
media to whole units. A cost or yield rounded to zero causes a blunder.

The reverse exchange is available through Hex Casting's **Knotweaver's Gambit**.
At the same rate, its input of 1 consumes 10000 media and stores 64 mana in knots.
Both mods must be installed.
