```json
{
  "title": "Soul and Anima",
  "icon": "createmanaindustry:liquid_soul_bucket",
  "ordinal": 10,
  "fabric:load_conditions": {
    "condition": "fabric:all_mods_loaded",
    "values": [
      "hexcasting",
      "trickster"
    ]
  },
  "associated_items": [
    "hexcasting:battery",
    "createmanaindustry:liquid_soul_bucket"
  ],
  "category": "createmanaindustry:mana_industry"
}
```

The soul used by Great Tricks is measured in **Anima**. A Spout can fill a Hex Casting **Media Battery** with Liquid Soul. A bucket supplies **32 Anima** by default, adjustable by the server.

Liquid Soul can be condensed from the soul mist produced by a burning Allay Burner. Condensation requires Coolant.

;;;;;

<|page-title@lavender:book_components|title=Battery Capacity|>

Soul capacity follows the battery's actual maximum media capacity. By default, each amethyst-dust equivalent of capacity allows **0.1 Anima**.

A battery holding 64,000 dust equivalents can therefore also store 6,400 Anima. Media and soul have separate balances; filling soul does not change the media already stored.

;;;;;

<|page-title@lavender:book_components|title=Paying for a Trick|>

Carry batteries holding Anima in the inventory. Casting draws from them in inventory order, using several batteries if needed.

An insufficient balance causes a blunder without making a partial soul payment. Near capacity, a Spout accepts only fluid that fits. Anima cannot currently be recovered through an Item Drain.
