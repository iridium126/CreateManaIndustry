```json
{
  "title": "Liquid Mana",
  "icon": "createmanaindustry:liquid_mana_bucket",
  "ordinal": 1,
  "associated_items": [
    "createmanaindustry:liquid_mana_bucket",
    "trickster:esoteric_mana"
  ],
  "category": "createmanaindustry:mana_industry"
}
```

Mana can be carried through pipes. A bucket of Liquid Mana contains **2,048 G** by default; the server may change this amount. Fluid quantities given here assume the default setting.

Liquid Mana supplies **traditional mana**. It does not refill Knots holding other mana types.

;;;;;

<|page-title@lavender:book_components|title=Drawing from Knots|>

Pass a [Knot](^trickster:items/mana/knots) holding traditional mana through an Item Drain to recover it as Liquid Mana. A Spout can refill a Knot with that same fluid, up to the Knot's own capacity.

The fluid is also used in [Knot production](^createmanaindustry:mana_industry/incomplete_knots).

;;;;;

<|page-title@lavender:book_components|title=Bottling Mana|>

Fill a Glass Bottle with **250 mB** of Liquid Mana to obtain [Esoteric Mana](^trickster:items/mana/esoteric_mana). Each bottle represents 512 G. Emptying it through an Item Drain returns the Glass Bottle.

A [Kinetic Mana Generator](^createmanaindustry:mana_industry/charging_knots) can supply the Knots from which this mana is drawn.
