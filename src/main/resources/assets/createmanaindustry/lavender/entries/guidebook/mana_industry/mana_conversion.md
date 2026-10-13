```json
{
  "title": "Mana and Media",
  "icon": "hexcasting:battery",
  "ordinal": 8,
  "fabric:load_conditions": {
    "condition": "fabric:all_mods_loaded",
    "values": [
      "hexcasting",
      "trickster"
    ]
  },
  "additional_search_terms": [
    "转化者之戏法",
    "mana_to_media"
  ],
  "category": "createmanaindustry:mana_industry"
}
```

Traditional mana and Hex Casting media can be exchanged. The [Ploy of the Transmuter](^createmanaindustry:mana_industry/mana_conversion#1) turns mana into media. Hex Casting's **Knotweaver's Gambit** performs the reverse exchange.

Requires Hex Casting.

;;;;;

<|trick@trickster:templates|trick-id=createmanaindustry:mana_to_media|>

Accepts a positive Number of gandalfs to spend. Returns the media produced, measured in amethyst-dust equivalents.

For a player, the output is stored in rechargeable media containers in the inventory. Prepare a Media Battery or another suitable container with enough room before casting.

;;;;;

<|page-title@lavender:book_components|title=The Exchange Rate|>

The rate follows the server's time spent processing each Tick. A busier world gives less media for each gandalf, and more mana in the reverse exchange.

Missing containers or insufficient room cause a blunder. There must be room for the complete output; neither a partial deposit nor an advance mana debit is made.

;;;;;

<|page-title@lavender:book_components|title=Construct Casting|>

In either type of Spell Construct, the inserted Knot provides mana and the output enters the [construct's media reserve](^createmanaindustry:mana_industry/hexcasting_bridge/construct_media). All four cores share that reserve.

If the Knot cannot pay, or the reserve cannot hold the output, both balances remain unchanged.
