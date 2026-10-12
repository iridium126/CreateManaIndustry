# Create: Mana Industry

[中文](./README_zh.md)

Create: Mana Industry is a NeoForge addon for **Minecraft 1.21.1** and **Create**. It adds Create
machinery, magical fluids, and integrations for moving resources between kinetic and magic systems.
The project also includes experimental GPU rendering, logistics, and an expanded-height dimension;
those systems are still under active development.

## Features

### Create machines, fluids, and recipes

- **Kinetic Mana Generator** converts Create stress into mana; its stress-per-RPM setting can be
  adjusted with the in-world scroll box. With Trickster installed, it can charge knots in compatible
  constructs and charging arrays.
- **Kinetic Atomizer** turns supported piped fluids into a spatial mist field. The field radius
  scales with rotational speed, and concentration falls with distance. Custom recipes can require
  mist or emit it as a byproduct.
- **Condenser** uses a separate Coolant line to recover fluid from nearby mist and sends the result
  to an Item Drain below.
- **Allay Burner** is a Create heat source built around a captured allay and fueled by amethyst
  materials or Liquid Media. It supports records and releases Liquid Soul mist while burning; basins
  can request its `allayheated` heat level.
- Adds Liquid Mana, Liquid Media, Liquid Source, Liquid Soul, Coolant, Molten Rose Quartz, and
  Molten Prismarine Quartz. Create pipes and tanks can move the fluids; optional magic-mod
  integrations add their respective resource bridges.
- Adds heated compacting, mist compacting/mixing, vaporizing, and vapor-deposition processing.
  Recipes cover amethyst-to-media conversion, quartz processing, and sheets coated with amethyst and
  rose quartz.
- The included heated mixing recipe converts 250 mB of Liquid Media into 125 mB of Liquid Mana. A
  superheated mist recipe turns 250 mB of Liquid Mana into a Liquid Media mist emission. Ice can be
  compacted into Coolant.
- Adds connected Molten Salt Fuel Tanks and the fuel-rod multiblock structure.

### Hexcasting

With Hexcasting installed, Create can automate Cypher, Trinket, Artifact, and Media Battery
production: a Deployer writes patterns, a Spout adds Liquid Media, and a Mechanical Press finalizes
the item while preserving its data. Media Batteries can also be filled and drained through Create
fluid networks.

Other integrations include slate stonecutting recipes for non-great-spell patterns, actions for
reading an Iota from an item on a Depot or Placard and lighting Blaze Burners or Allay Burners, plus
querying players in spell circles. A configurable server-side Hex JIT compiles hot call
sites; its default mode is `AUTO` and can be changed in the server config.

The in-game guide content is packaged as Patchouli entries with English and Simplified Chinese
localization.

### Trickster, Bits 'n' Bobs, and Ars Nouveau

- Trickster adds knot assembly and filling, mechanical-arm interaction with knots and constructs,
  Display Link targets, a temporary kinetic-stress trick, and mana/media exchange tricks.
- The Kinetics Spell Core exposes cogwheel-chain stress from Create: Bits 'n' Bobs to Trickster
  spells.
- Ars Nouveau integration provides Liquid Source support and source-jar fluid handling. Source
  Berry recipes are available only when Ars Nouveau is installed.

### Experimental systems

These systems are present in the codebase but are not yet considered fully game-verified. The
[development index](./docs/README.md) links to implementation notes and known gaps.

- **ALLVR Allay Dimension** adds a custom dimension with standard-height terrain and cube storage
  above and below it. Dimension rendering, block-entity synchronization, and some vanilla tick
  behavior remain in development. Sodium is required on clients.
- **Allay Storm** is a persistent, server-managed GPU swarm encounter. Operators can start it with
  `/cmi particle allaystorm [count]` and stop it with `/cmi particle allaystorm stop`; the command
  requires permission level 2. Encounter balance and behavior still need in-game validation.
- **GPU particle engine** handles high-volume particles, model particles, and Hexcasting effects.
  Client settings include a frame budget and automatic emission throttling. Shader-pack integration
  is available, with compatibility depending on the installed renderer versions.
- **GPU Create package simulation** is an opt-in experiment. It is disabled by default and requires
  matching client and server settings.

## Dependencies

Java 21 is required.

| Dependency | Requirement |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.236 or newer |
| Create | 6.0.10 or newer |
| Sodium | 0.8.13 on every client; not needed on a dedicated server |

The following mods are optional. Their integration is enabled when the mod is installed:

| Mod | Minimum version | Integration |
|---|---:|---|
| [Trickster](https://modrinth.com/mod/trickster) | 2.0.0-beta.56 | Knots, spells, constructs, and kinetic links |
| [Create: Bits 'n' Bobs](https://modrinth.com/mod/create-bits-n-bobs) | 2.1.9-beta | Cogwheel-chain data for the Kinetics Spell Core |
| [Hexcasting](https://modrinth.com/mod/hexcasting) | 0.12.0-devel-pre-53 | Casting-item automation, media storage, and custom actions |
| HexParse | 1.21.1-1.11.2.pre53 | Adapters for integration-specific Hexcasting data |
| [Veil](https://modrinth.com/mod/veil) | 4.1.4 | Volumetric mist and related rendering effects |
| [Ars Nouveau](https://modrinth.com/mod/ars-nouveau) | 5.13.0 | Liquid Source and source-jar handling |

## Configuration

NeoForge creates `createmanaindustry-server.toml` and `createmanaindustry-client.toml` in the config
directory. Gameplay and balance values are server-side; rendering and particle controls are
client-side. Each file includes comments for every setting.

Common server settings:

| Setting | Default | Purpose |
|---|---:|---|
| `fluid.manaPerBucket` | 2048 | Mana represented by one bucket of Liquid Mana |
| `fluid.mediaPerBucket` | 400000 | Media represented by one bucket of Liquid Media |
| `kinetics.manaPerStress` | 0.001 | Mana produced per stress unit per tick |
| `mist.mistMaxRadius` | 16 | Maximum atomizer mist radius in blocks |
| `mist.mistFluidPerTick` | 8 | Base atomizer fluid use at 256 RPM, in mB/tick |
| `mist.condenseEfficiency` | 5.0 | Condenser base output per concentration unit, in mB/tick |
| `fuel_tank.fuelTankCapacity` | 8 | Buckets stored per fuel-tank block |
| `hexcasting.jit.mode` | `AUTO` | Hex JIT mode: `OFF`, `PROFILE`, or `AUTO` |

The server file also has settings for Allay Burner fuel, fuel-rod structure limits, Hexcasting item
capacities, and Allay Storm behavior. The client file controls mist glow, GPU particle limits and
throttling, Hexcasting particle redirects, and ALLVR LOD. GPU package authority is disabled by
default on both sides.

## Build

Use Java 21 and run:

```bash
./gradlew build
```

On Windows, run `.\gradlew.bat build`. The installable mod jar is written to
`build/libs/createmanaindustry-0.2.6.jar`. The bootstrap-only jar used by development launches is
kept under `build/internal/` and is not a separate player-installation file.

When Connector is installed, the bundled early startup service recognizes and patches the verified
Trickster `2.0.0-beta.56` jar before Connector scans Fabric mods. It checks the target class hashes,
saves the original beside the jar as `.jar.backup`, and leaves unrecognized or modified jars
untouched. In development runs, Gradle copies the Trickster dependency into `run/mods/` before
launch so the same service can process it there.

## Development documentation

See [docs/README.md](./docs/README.md) for the development-documentation index, implementation
status, and known gaps. It covers GPU particles and packages, ALLVR, Allay Storm, and shader-pack
integration.

## License

MIT License
