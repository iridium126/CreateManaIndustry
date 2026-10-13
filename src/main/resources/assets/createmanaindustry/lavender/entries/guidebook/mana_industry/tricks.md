```json
{
  "title": "Industrial Ploys",
  "icon": "trickster:amethyst_knot",
  "ordinal": 7,
  "additional_search_terms": [
    "动力应力戏法",
    "temporary_kinetic_stress"
  ],
  "category": "createmanaindustry:mana_industry"
}
```

These ploys put mana to work in kinetic machinery. They require Trickster and Create, without requiring Hex Casting. The [reading and execution tricks](^createmanaindustry:mana_industry/hexcasting_bridge/iota_bridge) used for the Hexcasting bridge are listed separately.

;;;;;

<|ploy@trickster:templates|trick-id=createmanaindustry:temporary_kinetic_stress,cost=0.008 × |speed| × ticks G|>

Temporarily provides rotational speed and a stress source to the kinetic block at the given position. Returns the target Vector.

Inputs are **position, speed, duration in Ticks**, in that order. A negative speed reverses rotation. Duration is rounded down and must remain positive; zero speed is invalid.

;;;;;

<|page-title@lavender:book_components|title=Duration and Cost|>

The default cost is **0.008 × absolute speed × duration in Ticks G**. A speed of 32 for 20 Ticks costs 5.12 G. The server can adjust the stress conversion rate and cost multiplier.

When the duration expires, the temporary source is removed and ordinary kinetic connections take over again. The target must be a loaded kinetic block.
