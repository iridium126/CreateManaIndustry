```json
{
  "title": "Kinetic Mana Generator",
  "icon": "createmanaindustry:kinetic_mana_generator",
  "ordinal": 3,
  "associated_items": [
    "createmanaindustry:kinetic_mana_generator"
  ],
  "category": "createmanaindustry:mana_industry"
}
```

The Kinetic Mana Generator uses rotation to refill [Knots](^trickster:items/mana/knots). Place a [Charging Array](^trickster:items/mana/charging_array), [Spell Construct](^trickster:items/infrastructure/spell_construct), or [Modular Spell Construct](^trickster:items/infrastructure/modular_spell_construct) immediately against its output face. Insert a Knot and connect rotational power.

Its shaft connection is on the opposite face.

;;;;;

<|page-title@lavender:book_components|title=Stress and Charging|>

The scroll control sets the stress demand per unit of rotational speed, from **4 to 256**, with a default of 4.

With default settings, mana supplied each Tick is:

**stress setting × absolute speed × 0.001 G**

At a setting of 4 and a speed of 256, the generator supplies 1.024 G per Tick. The server can adjust the conversion rate.

;;;;;

<|page-title@lavender:book_components|title=Receiving the Mana|>

A Charging Array divides the supply by the number of Knots it contains. Both types of Spell Construct receive mana through their central Knot. Only containers able to accept traditional mana benefit; full or infinite Knots cannot store more.

Charging stops without rotation or while the network is overstressed.

;;;;;

<|page-title@lavender:book_components|title=Moving Knots|>

Mechanical Arms can use Charging Arrays and both types of Spell Construct as input or output points. Arrays expose all their Knot slots. Constructs expose only their central Knot slot, not their Spell Cores.

Take charged Knots away for later use, or leave them in the construct that draws from them.
