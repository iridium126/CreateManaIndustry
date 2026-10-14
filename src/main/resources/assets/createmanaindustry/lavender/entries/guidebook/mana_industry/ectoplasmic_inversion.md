```json
{
  "title": "Ectoplasmic Inversion",
  "icon": "hexcasting:battery",
  "ordinal": 11,
  "fabric:load_conditions": {
    "condition": "fabric:all_mods_loaded",
    "values": [
      "hexcasting",
      "trickster"
    ]
  },
  "additional_search_terms": [
    "灵质反转",
    "卓越戏法",
    "Anima",
    "真名"
  ],
  "category": "createmanaindustry:mana_industry"
}
```

A [Great Trick](^createmanaindustry:mana_industry/great_tricks). Takes no inputs and returns a Boolean fragment indicating whether inversion is active after the cast. Each casting costs **4 Anima**.

Within one casting chain, cast once to enable inversion and return `true`, then cast again to disable it and return `false`. Disabling also costs soul. An insufficient balance leaves the switch unchanged.

;;;;;

<|great-trick@createmanaindustry:book_components|trick-id=createmanaindustry:ectoplasmic_inversion|>

;;;;;

<|page-title@lavender:book_components|title=Inverting Reach|>

Hex Casting's ordinary spatial ambit becomes its exterior, including sentinel extensions. Its ordinary true-name distance exemption remains effective.

Trickster's existing close-range reading, writing, channel and mana-converter checks are also inverted. Unrestricted tricks gain no new distance limit. World bounds, loaded chunks and editing permissions still apply.

;;;;;

<|page-title@lavender:book_components|title=Exchanging Costs|>

Operations that normally spend media instead spend mana, and those spending mana instead spend media. The equivalent payment is **doubled**.

Equivalence compares the server's media and mana content per bucket. It does not use the [Transmuter's](^createmanaindustry:mana_industry/mana_conversion) workload-dependent rate or change actual fluid recipes.

;;;;;

<|page-title@lavender:book_components|title=Default Cost Examples|>

With default settings, a bucket of Liquid Media represents 40 amethyst-dust equivalents, and a bucket of Liquid Mana holds 2,048 G.

- A cost of 1 dust becomes 102.4 G.
- A cost of 1,024 G becomes 40 dust equivalents of media.

Media payments retain Hex Casting's ordinary overcasting conditions. Anima is not exchanged.

;;;;;

<|page-title@lavender:book_components|title=Passing Your Own True Name|>

While inversion is active, true-name scans from any player or casting environment skip your true name, including playerless circles and scans from another dimension.

Other unprotected true names can still cause a mishap. Skipping yours does not exempt the whole List or remove other requirements for the operation.

;;;;;

<|page-title@lavender:book_components|title=For This Casting Chain|>

Sub-spells, cross-system calls and long-running continuations retain the same switch. Independent casts do not inherit its range or cost changes.

The exemption ends when all branches finish, are cancelled, or the caster disconnects. If another inverted chain belonging to the same player remains, their name exemption persists until the last one is disabled or ends.
