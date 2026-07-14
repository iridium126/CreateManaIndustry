# Create: Mana Industry

A NeoForge addon for **Create** that bridges kinetic (rotational) power with magical
mods: convert stress into mana, atomize fluids into volumetric mist, and automate
spell-crafting — all through Create's mechanical systems.

## Features

- **Kinetic Mana Generator** — consumes Create rotational stress to produce mana,
  charging Trickster knots placed in Spell Constructs or Charging Arrays
- **Kinetic Atomizer** — atomizes piped fluids into a volumetric mist field,
  fueling recipes or harvestable by the Condenser
- **Condenser** — condenses mist back into liquid when water flows through it,
  injecting the recovered fluid into a Create Item Drain below
- **Mist recipes** — Mechanical Press and Mixer can require mist as a condition
  or release mist as a byproduct (Mist Compacting / Mist Mixing recipe types)
- **Heated Compacting** — new heated basin recipe type for the Mechanical Press
- **Liquid Mana** — a full-bright fluid that stores mana in fluid form;
  compatible with Create's pipe and tank networks
- **Liquid Media** — a fluid bridge to Hexcasting media batteries
- **Trickster trick:** `temporary_kinetic_stress` — temporarily applies stress and
  speed to any kinetic block at a mana cost
- **Sequenced assembly** — craft Trickster knots via Create's assembly line:
  incomplete knot → Spout filling with Liquid Mana → Mechanical Press
- **Display Link targets** — write text into Trickster Spell Construct and
  Modular Spell Construct arguments
- **Mechanical Arm support** — arms can insert/extract knot items from Trickster
  block entities
- **Veil post-processing** — volumetric mist rendering when Veil is installed

## Dependencies

### Required
- [NeoForge](https://neoforged.net) (1.21.1)
- [Create](https://createmod.net)

### Optional
- [Hexcasting](https://modrinth.com/mod/hexcasting) — Liquid Media ↔ media battery bridge
- [Trickster](https://modrinth.com/mod/trickster) (loaded via Sinytra Connector)
- [Veil](https://modrinth.com/mod/veil) — volumetric mist shader rendering
- [Create: Bits 'n' Bobs](https://modrinth.com/mod/create-bits-n-bobs) — cogwheel chain integration

## Build

```bash
./gradlew build
```

## License

MIT License
