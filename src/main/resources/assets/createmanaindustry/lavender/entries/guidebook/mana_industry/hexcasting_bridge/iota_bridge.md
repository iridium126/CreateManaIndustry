```json
{
  "title": "Fragments and Iotas",
  "icon": "trickster:amethyst_knot",
  "category": "createmanaindustry:mana_industry/hexcasting_bridge",
  "ordinal": 0,
  "fabric:load_conditions": {
    "condition": "fabric:all_mods_loaded",
    "values": [
      "hexcasting",
      "trickster"
    ]
  },
  "additional_search_terms": [
    "读取Iota",
    "运行Iota",
    "iota"
  ]
}
```

Hex Casting represents information as **iotas**; the Great Language uses **fragments**. Numbers, Vectors, Booleans and Lists can convert directly to their corresponding fragments. Other iotas are carried by an **Iota Fragment**.

Both Hex Casting null and garbage become void on the Trickster side. The two systems' empty values are not identical.

;;;;;

<|trick@trickster:templates|trick-id=createmanaindustry:read_iota|>

Reads an iota from an item in the given Slot and returns its corresponding fragment. Omitting the Slot selects the other hand.

The reading range is **16 blocks**. An item without a readable iota causes a blunder, rather than producing an empty spell.

;;;;;

<|trick@trickster:templates|trick-id=createmanaindustry:eval_iota|>

Accepts an **Iota Fragment** containing an executable pattern or Hex list. The remaining inputs become the initial Hex Casting stack.

On success, returns the top of that stack, or the empty value corresponding to null if the stack is empty. A failed execution returns void. Hex Casting's media supply pays the cost.

;;;;;

<|page-title@lavender:book_components|title=The Casting Environment|>

Player casting uses the player's media items and permits overcasting when the ordinary conditions are met. No staff needs to be held.

Construct casting uses the [internal media reserve](^createmanaindustry:mana_industry/hexcasting_bridge/construct_media), with a 32-block ambit centred on the construct. Operations requiring a player or held item still require one.

;;;;;

<|page-title@lavender:book_components|title=Sending a Trick to Hex Casting|>

Hex Casting's **Unknotting** reads a Trickster spell into a Trick Iota, carrying its entire spell tree. **Hermes' Gambit** can then run it.

Place the argument List below the Trick Iota. A spell completed within one Tick can return its result to the stack; a spell handed off for later execution does not. See the Hex Casting book's Trickster Patterns entry.
