```json
{
  "title": "Potion Fragments",
  "icon": "minecraft:potion",
  "ordinal": 13,
  "fabric:load_conditions": {
    "condition": "fabric:all_mods_loaded",
    "values": [
      "trickster"
    ]
  },
  "category": "createmanaindustry:mana_industry"
}
```

A Potion fragment stores one complete potion-effect snapshot: its registered type, remaining duration, level, ambient and display flags, hidden fallback effects and NeoForge cures. Registered effects from other mods are supported too.

The stored time stands still. Changes to the original entity do not change the fragment, and each application creates a separate effect instance.

;;;;;

<|trick@trickster:templates|trick-id=createmanaindustry:read_potion_effects|>

Takes an Entity and returns a List of Potion fragments, one for each active effect, sorted by effect ID. Hidden fallback effects stay inside their owning fragment. This query spends no mana, media or Anima and has no distance limit.

The target must resolve in the current dimension and be in an entity-ticking chunk. A target without effects, including a nonliving entity, returns an empty List.

;;;;;

<|page-title@lavender:book_components|title=Keeping an Effect|>

Copies, spell trees and saved long-running spells preserve the complete snapshot, including infinite duration. The fragment shows the effect's name, level and remaining time.

With Hex Casting installed, Tincture Shaping can apply the saved effect to a living entity for 32 Anima.
