"""Prepare a dedicated GameTest directory without touching player saves."""
from pathlib import Path
import gzip
import struct
import shutil

repo = Path(__file__).resolve().parents[2]
def string(s):
    b = s.encode()
    return struct.pack('>H', len(b)) + b
def tag(t, name, value):
    return bytes([t]) + string(name) + value
nbt = tag(10, '', tag(3, 'DataVersion', struct.pack('>i', 3955))
    + tag(9, 'size', b'\x03' + struct.pack('>4i', 3, 1, 1, 1))
    + tag(9, 'entities', b'\x0a' + struct.pack('>i', 0))
    + tag(9, 'blocks', b'\x0a' + struct.pack('>i', 0))
    + tag(9, 'palette', b'\x0a' + struct.pack('>i', 1)
      + tag(8, 'Name', string('minecraft:air')) + b'\0') + b'\0')
target = repo / 'build/markov-test-resources/data/createmanaindustry/structure/markov_test.nbt'
target.parent.mkdir(parents=True, exist_ok=True)
target.write_bytes(gzip.compress(nbt))
mods = repo / 'build/markov-test-run/mods'
mods.mkdir(parents=True, exist_ok=True)
for library in (repo / 'run/mods').glob('owo-lib-neoforge-*.jar'):
    shutil.copy2(library, mods / library.name)
print('Prepared isolated MarkovJunior GameTests')
