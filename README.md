# Create: Mana Industry

[中文](./README_zh.md)

Create: Mana Industry is a NeoForge addon for **Minecraft 1.21.1** and **Create**. It adds Create
machinery that turns rotational power into mana and media, magical fluids and volumetric mist
fields, and integrations that move resources between Create, Trickster, Hexcasting, and Ars
Nouveau. The project also includes experimental GPU rendering and upscaling, GPU-driven Create
package simulation, Allay dimension and Allay Storm.

## Features

### Create machines, fluids, and recipes

- **Kinetic Mana Generator** consumes rotational stress and, with Trickster installed, charges
  Knots in an adjacent Charging Array or Spell Construct. Its scroll box sets stress per RPM from
  4 to 256 (default 4); the mana supplied each tick is `stress × speed × manaPerStress`.
- **Mana Cogwheel** generates rotation from a mist field. Liquid Mana mist drives it at 256 RPM
  and Liquid Media mist at −256 RPM; it provides 8 × absolute speed SU of stress capacity and
  stops when the field disappears.
- **Kinetic Atomizer** turns a piped, non-molten fluid into a volumetric mist field. Radius scales
  with rotational speed and concentration falls with distance. Water mist above 10% hydrates
  farmland; mist from molten fluids ignites and burns living entities.
- **Condenser** consumes coolant flowing through its axis one-to-one with the fluid it recovers
  from nearby mist, then sends the result to an Item Drain below. Copper weathering and waxed
  variants are available.
- **Allay Burner** is a Create heat source built around a captured allay. It burns Amethyst Shards,
  Hexcasting Amethyst Dust or Charged Amethyst, and Liquid Media; it plays records, emits Liquid
  Soul mist, and provides the `allayheated` heat level.
- Adds Liquid Mana, Liquid Media, Liquid Source, Liquid Soul, Coolant, and Molten Rose Quartz.
- Adds `heated_compacting`, `mist_mixing`, `vaporizing`, and `vapor_deposition` recipe types.
  Amethyst materials compact into Liquid Media; heated mixing turns 250 mB Liquid Media into
  125 mB Liquid Mana; superheated mist mixing turns 250 mB Liquid Mana into Liquid Media mist;
  glass becomes Amethyst Blocks under Liquid Media mist and `allayheated` heat; ice compacts into
  Coolant.
- A **Framed Glass Trapdoor** placed over a Basin becomes a **Deposition Lid**; closing it seals
  the basin. Sealed basins run `vapor_deposition` recipes: amethyst or rose quartz mist coats iron,
  copper, gold, and brass sheets, and with Hexcasting, glass can also become a Scrying Lens.
- Create **Ponder** scenes cover the Allay Burner, mist fields, the Mana Cogwheel, deposition, and
  the Trickster and Hexcasting production lines.

### Hexcasting

With Hexcasting installed, Create can automate Cypher, Trinket, Artifact, and Media Battery
production. A Deployer copies patterns from an Iota-bearing item onto an incomplete casting item;
a Spout fills it with Liquid Media; a Mechanical Press finalizes it while preserving patterns,
Iotas, media, and dye. Media Batteries are made from a Make Phial scroll and a Glass Bottle, and
exchange Liquid Media with Create fluid networks; a Spout fills their Anima reserve from Liquid
Soul.

Other integrations include stonecutting recipes for non-great-spell patterns, actions for reading
an Iota from an item on a Depot or Placard, lighting Blaze Burners or Allay Burners, and querying
players in spell circles.

Guide content is available as a Lavender category extending Trickster's guidebook and as a Mana
Industry category in Hexcasting's Patchouli book, with English and Simplified Chinese
localization.

### Trickster, Bits 'n' Bobs, and Ars Nouveau

- Trickster integration adds automated Knot production and filling, Mechanical Arm access to
  Knots and constructs, Display Link targets, and a temporary kinetic-stress ploy. Deployers use
  glass and materials to produce Knots. Higher tiers pass through an incomplete stage, which Spouts
  fill with Liquid Mana; finished Knots need no pressing, and a Mechanical Press can produce
  cracked variants.
- **Great Tricks** (with Hexcasting installed) add a hexagonal lattice editor and Anima costs:
  Ectoplasmic Inversion swaps reach and media/mana costs, Shared Fate mirrors mishaps, Tincture
  Shaping applies stored potion effects, and Partition separates space with a cubic boundary.
- **Anima** is the soul cost of Great Tricks. A Spout fills a Hexcasting Media Battery's Anima
  reserve with Liquid Soul (32 Anima per bucket by default); the soul is condensed from the Liquid
  Soul mist of a burning Allay Burner using Coolant.
- **Potion fragments** captured through a Trickster trick preserve a potion effect's type,
  duration, level, and flags for later use.
- The **Kinetics Spell Core** is a Trickster Spell Core driven by a Create: Bits 'n' Bobs cogwheel
  chain. Chain speed sets the execution budget, and each core adds 4 × absolute speed SU of load.
- Ars Nouveau integration provides Liquid Source and source-jar fluid handling. Source Berry
  recipes are available only when Ars Nouveau is installed.

### Experimental systems

These systems are under development.

- **Allay Storm** is a server-managed, client-GPU-driven group boss with Starfall columns, mana
  beams, Helix, and Dome attacks. At 16,384 generated members, an overworld storm can open a sky
  rift above build height and teleport eligible players into the Allay dimension. Operators can use
  `/cmi particle allaystorm [count]`, `/cmi particle allaystorm status`, and
  `/cmi particle allaystorm stop` to test the Allay Storm. On Windows, the native RT backend uses
  CUDA/OptiX and falls back to OpenGL.
- **GPU particle engine** handles high-volume particles, model particles, persistent block
  emitters, and Hexcasting effects. Client settings include capacity, a frame budget, and automatic
  emission throttling. Shader-pack integration is available, with compatibility depending on the
  installed renderer versions.
- **Windows x64 upscaling and frame generation**: NVIDIA DLSS Super Resolution on supported RTX
  GPUs and Intel XeSS Frame Generation on supported Arc GPUs are disabled by default.

## Dependencies

Java 21 is required.

| Dependency | Requirement |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.236 or newer |
| Create | 6.0.10 or newer |
| Sodium | 0.8.13 or newer |

The following mods are optional. Their integration is enabled when the mod is installed:

| Mod | Minimum version | Integration |
|---|---:|---|
| [Hexcasting](https://modrinth.com/mod/hexcasting) | 0.12.0-devel-pre-64 | Casting-item automation, media storage, and custom patterns |
| [Trickster](https://modrinth.com/mod/trickster) | 2.0.0-beta.56 | Knots, spells, constructs, and kinetic links |
| [Veil](https://modrinth.com/mod/veil) | 4.1.4 | Volumetric mist |
| [Iris Veil Compat](https://github.com/leon-o/iris-veil-compat) | 0.4.0 | Shaderpack integration for mist |
| [Ars Nouveau](https://modrinth.com/mod/ars-nouveau) | 5.13.0 | Liquid Source |
| [Create: Bits 'n' Bobs](https://modrinth.com/mod/create-bits-n-bobs) | 2.1.9-beta | Kinetics Spell Core |

## Configuration

NeoForge creates `createmanaindustry-server.toml` and `createmanaindustry-client.toml` in the config
directory. Gameplay and balance values are server-side; rendering and particle controls are
client-side. Each file includes comments for every setting.

Common server settings:

| Setting | Default | Purpose |
|---|---:|---|
| `fluid.manaPerBucket` | 2048 | Mana represented by one bucket of Liquid Mana |
| `fluid.mediaPerBucket` | 400000 | Media represented by one bucket of Liquid Media |
| `fluid.animaPerBucket` | 32 | Anima represented by one bucket of Liquid Soul |
| `kinetics.manaPerStress` | 0.001 | Mana produced per stress unit per tick |
| `mist.mistMaxRadius` | 16 | Maximum atomizer mist radius in blocks |
| `mist.mistFluidPerTick` | 8 | Base atomizer fluid use at 256 RPM, in mB/tick |
| `mist.condenseEfficiency` | 5.0 | Condenser base output per concentration unit, in mB/tick |

The server file also has settings for Allay Burner fuel use and mist, Hexcasting item capacities,
battery Anima capacity, Allay Storm population and attacks, and the Hex JIT. The client file
controls mist glow and shadow debugging, GPU particle capacity/budget/throttling, Allay Storm
simulation and LOD, DLSS and XeSS-FG, Hexcasting particle and pattern redirection, Great Trick grid
snapping, and package budgets.

## Build

Use Java 21. The first `build` prepares the pinned Hexal and Voxy dependencies when their caches are
missing; later builds skip those steps.

```bash
bash ./gradlew build
```

On Windows, use `.\gradlew.bat`.

To load Trickster on NeoForge, the bundled early startup service verifies the hashes of Trickster's
target classes before Connector scans Fabric mods. When they match, it applies the patch and saves
the original beside the jar with a `.jar.backup` suffix.

## Acknowledgements

- **xm1221** — wrote part of the Hexcasting guidebook
- **YukkuriC** — HexParse help spell testing
- **拟故喵喵** — contributed part of the art design
- **七七** — provided complex test environments
- **cang_hai_yi_su** — drew primary cell textures
- **C8N16O32** — provided test spells

## License

MIT License
