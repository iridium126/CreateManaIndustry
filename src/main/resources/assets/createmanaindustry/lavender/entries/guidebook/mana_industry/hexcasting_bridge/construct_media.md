```json
{
  "title": "Media in Constructs",
  "icon": "createmanaindustry:liquid_media_bucket",
  "category": "createmanaindustry:mana_industry/hexcasting_bridge",
  "ordinal": 1,
  "fabric:load_conditions": {
    "condition": "fabric:all_mods_loaded",
    "values": [
      "hexcasting",
      "trickster"
    ]
  },
  "associated_items": [
    "trickster:spell_construct",
    "trickster:modular_spell_construct"
  ]
}
```

A Spell Construct can hold a separate media reserve for [Hex Casting execution](^createmanaindustry:mana_industry/hexcasting_bridge/iota_bridge). This reserve and the central Knot supply different resources: having mana does not imply having media.

All four cores in a Modular Spell Construct share the same media reserve.

;;;;;

<|page-title@lavender:book_components|title=Adding Media|>

Insert media items eligible for battery construction, such as Amethyst Dust, Amethyst Shards or Charged Amethyst. Hoppers and other item transport can supply them automatically.

An ordinary Media Battery can fuel a player, but cannot be emptied into this reserve. Casting the [Ploy of the Transmuter](^createmanaindustry:mana_industry/mana_conversion) from the construct is another way to replenish it.

;;;;;

<|page-title@lavender:book_components|title=Persistence|>

The reserve is saved with the construct; unloading its chunk does not empty its fuel. It provides neither a player's inventory nor an offhand item, so spells must still satisfy their own requirements.

In creative mode, inserting a Creative Unlocker makes this media reserve unlimited.
