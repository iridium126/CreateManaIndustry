```json
{
  "title": "Tincture Shaping",
  "icon": "hexcasting:battery",
  "ordinal": 14,
  "fabric:load_conditions": {
    "condition": "fabric:all_mods_loaded",
    "values": [
      "hexcasting",
      "trickster"
    ]
  },
  "category": "createmanaindustry:mana_industry"
}
```

A [Great Trick](^createmanaindustry:mana_industry/great_tricks). Takes an Entity and a [Potion fragment](^createmanaindustry:mana_industry/potion_fragments), in that order. A successful cast costs **32 Anima** and returns the same Entity fragment.

Applies a fresh copy of the complete stored effect to the living target. You may target yourself.

;;;;;

<|great-trick@createmanaindustry:book_components|trick-id=createmanaindustry:tincture_shaping|>

;;;;;

<|page-title@lavender:book_components|title=Reach and True Names|>

The target must resolve in the current dimension and be in an entity-ticking chunk. Hex Casting's entity ambit applies, including sentinel extensions and this chain's Ectoplasmic Inversion.

Its configured player true-name distance exemption also applies: an exempt player target can be reached beyond ordinary spatial limits. Other targets retain the spatial range check.

;;;;;

<|page-title@lavender:book_components|title=Merging the Tincture|>

Existing effects merge by Minecraft's ordinary level, duration and display rules. Entity immunity and mod effect events remain effective. A change to hidden fallback effects also counts as success.

An instant effect is added as a stored status instance, as with `/effect`, preserving its duration. It may act repeatedly on effect ticks; this is not a single drinking-potion pulse.

;;;;;

<|page-title@lavender:book_components|title=Paying in Soul|>

Insufficient Anima prevents application. Immunity, blocked application or an unchanged complete effect causes a blunder without payment. Successful applications can draw from several batteries.

The 32 Anima cost is unchanged by Ectoplasmic Inversion's exchanged and doubled media/mana costs.
