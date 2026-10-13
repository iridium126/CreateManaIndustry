```json
{
  "title": "Display Links and Arguments",
  "icon": "create:display_link",
  "ordinal": 5,
  "associated_items": [
    "create:display_link"
  ],
  "category": "createmanaindustry:mana_industry"
}
```

A Display Link can send a machine reading to a [Spell Construct](^trickster:items/infrastructure/spell_construct) as a spell argument.

Select the construct as the display target, then place the link on the block providing the reading. Choose its source content and the argument position to write. A simple construct accepts up to eight arguments.

;;;;;

<|page-title@lavender:book_components|title=Text, Not Numbers|>

Display Links write **String fragments**. A count displayed as "32" arrives as the String "32", rather than a Number. Parse the text before using it in arithmetic.

One written position may contain several lines. Unwritten positions retain their original arguments; empty positions added to reach a later argument are void.

;;;;;

<|page-title@lavender:book_components|title=Reading Again|>

Updates also reach an already-running spell. A long spell can repeatedly read an argument and respond to inventory counts or other machine readings.

A [Modular Spell Construct](^trickster:items/infrastructure/modular_spell_construct) has eight argument positions for each of its four cores. Choose both the intended core and argument when configuring the link.
