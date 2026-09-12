"""Prepare isolated GameTest resources. Never touches a player's run/saves directory."""
import argparse
import gzip
import json
from pathlib import Path
import shutil
import struct
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--terralith', type=Path, help='Path to an extracted Terralith datapack')
args = parser.parse_args()
repo = Path(__file__).resolve().parents[1]
mode = 'terralith' if args.terralith else 'vanilla'
root = repo / 'build' / ('allvr-worldgen-test-resources-' + mode)
run = repo / 'build' / ('allvr-worldgen-test-run-' + mode)


def string(text):
    encoded = text.encode()
    return struct.pack('>H', len(encoded)) + encoded


def tag(kind, name, payload):
    return bytes([kind]) + string(name) + payload


nbt = tag(10, '', tag(3, 'DataVersion', struct.pack('>i', 3955))
          + tag(9, 'size', b'\x03' + struct.pack('>4i', 3, 1, 1, 1))
          + tag(9, 'entities', b'\x0a' + struct.pack('>i', 0))
          + tag(9, 'blocks', b'\x0a' + struct.pack('>i', 0))
          + tag(9, 'palette', b'\x0a' + struct.pack('>i', 1)
                + tag(8, 'Name', string('minecraft:air')) + b'\0') + b'\0')
template = root / 'data/createmanaindustry/structure/worldgen_test.nbt'
template.parent.mkdir(parents=True, exist_ok=True)
template.write_bytes(gzip.compress(nbt))

flat = {'type': 'minecraft:overworld', 'generator': {'type': 'minecraft:flat', 'settings': {
    'layers': [{'height': 1, 'block': 'minecraft:bedrock'}], 'biome': 'minecraft:plains', 'structure_overrides': []}}}
terrain = {'biome_source': {'type': 'minecraft:multi_noise', 'preset': 'minecraft:overworld'}, 'settings': 'minecraft:overworld'}
if args.terralith:
    pack = args.terralith.resolve()
    terrain = json.loads((pack / 'data/minecraft/dimension/overworld.json').read_text())['generator']
    terrain.pop('type', None)
    archive = run / 'world/datapacks/terralith.zip'
    archive.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as output:
        for path in pack.rglob('*'):
            if path.is_file():
                output.write(path, path.relative_to(pack))
allay = {'type': 'createmanaindustry:allay', 'generator': {'type': 'createmanaindustry:allay_islands', 'terrain': terrain}}
preset = root / 'data/minecraft/worldgen/world_preset/flat.json'
preset.parent.mkdir(parents=True, exist_ok=True)
preset.write_text(json.dumps({'dimensions': {'minecraft:overworld': flat, 'createmanaindustry:allay_dimension': allay}}))

# This dependency is maintained in run/mods rather than Gradle by this repository.
for library in (repo / 'run/mods').glob('owo-lib-neoforge-*.jar'):
    (run / 'mods').mkdir(parents=True, exist_ok=True)
    shutil.copy2(library, run / 'mods' / library.name)
print(f'Prepared {mode}: {run}')
