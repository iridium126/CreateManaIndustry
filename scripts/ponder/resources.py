"""Generate/check bilingual Ponder text and reproducible NBT workshop stages.

No packages required. Run from any directory:
  python scripts/ponder/resources.py --write
  python scripts/ponder/resources.py --check
Java scenes are the source of truth for IDs, English and Chinese text. Machines
are staged by Java instructions; NBT contains the bounded, decorated workshop.
"""
from pathlib import Path
import argparse
import gzip
import json
import re
import struct

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'src/main/java/com/iridium126/createmanaindustry/ponder'
ASSETS = ROOT / 'src/main/resources/assets/createmanaindustry'
PREFIX = 'createmanaindustry.ponder.'
STRING = r'"((?:[^"\\]|\\.)*)"'
TITLE = re.compile(r'new Workshop\(builder, util, ' + STRING + r', ' + STRING + r', ' + STRING + r'\)')
TEXT = re.compile(r'w\.text\([^;]*?' + STRING + r',\s*' + STRING + r'\);', re.S)
REG = re.compile(r'add\(helper, "([a-z_]+)", (\w+)::(\w+), (\w+), ([^;]+)\);')
TAGS = {
    'processing': ('The Magic Workshop', '魔法工坊', 'Heat, coat and process materials with Create machinery', '用机械动力机器完成加热、镀层和材料加工'),
    'mist': ('Working with Mist', '雾场入门', 'Make mist, collect it and use it to power machines', '制造、回收雾，并用雾驱动机器'),
    'magic_automation': ('Magic Automation', '魔法自动化', 'Charge, assemble and connect magical items', '为魔法物品充能、组装，并接入自动化系统'),
}


def unquote(s):
    return json.loads('"' + s + '"')


def scenes():
    result = {}
    for path in sorted(JAVA.glob('*PonderScenes.java')):
        source = path.read_text(encoding='utf-8')
        matches = list(TITLE.finditer(source))
        for i, match in enumerate(matches):
            name, en, zh = map(unquote, match.groups())
            assert name not in result, f'Duplicate scene: {name}'
            body = source[match.end():matches[i + 1].start() if i + 1 < len(matches) else len(source)]
            paragraphs = [(unquote(a), unquote(b)) for a, b in TEXT.findall(body)]
            assert len(paragraphs) == body.count('w.text('), f'Unparsed text: {name}'
            assert 4 <= len(paragraphs) <= 7, f'Unexpected pacing: {name}'
            assert 'w.finish();' in body, f'Missing ending: {name}'
            result[name] = (en, zh, paragraphs, path.stem)
    registrations = REG.findall((JAVA / 'CMIPonderPlugin.java').read_text(encoding='utf-8'))
    assert {r[0] for r in registrations} == set(result), 'Registration/scene ID mismatch'
    assert len(registrations) == len(result), 'Duplicate registration IDs'
    for name, cls, method, tag, items in registrations:
        assert cls == result[name][3], f'Wrong storyboard class: {name}'
        source = (JAVA / (cls + '.java')).read_text(encoding='utf-8')
        assert f'void {method}(SceneBuilder builder, SceneBuildingUtil util)' in source
        assert re.findall(STRING, items), f'No component entry point: {name}'
    return result, registrations


def nbt_string(value):
    encoded = value.encode('utf-8')
    return struct.pack('>H', len(encoded)) + encoded


def payload(tag, value):
    if tag == 3:
        return struct.pack('>i', value)
    if tag == 8:
        return nbt_string(value)
    if tag == 9:
        subtype, values = value
        return bytes([subtype]) + struct.pack('>i', len(values)) + b''.join(payload(subtype, v) for v in values)
    if tag == 10:
        return b''.join(bytes([t]) + nbt_string(k) + payload(t, v) for k, (t, v) in value.items()) + b'\0'
    raise ValueError(tag)


def stage(name):
    palette, indices, blocks = [], {}, []
    def put(x, y, z, block, **properties):
        key = (block, tuple(sorted(properties.items())))
        if key not in indices:
            indices[key] = len(palette)
            entry = {'Name': (8, 'minecraft:' + block)}
            if properties:
                entry['Properties'] = (10, {k: (8, str(v)) for k, v in properties.items()})
            palette.append(entry)
        blocks.append({'pos': (9, (3, [x, y, z])), 'state': (3, indices[key])})
    accent = ('amethyst_block' if name.startswith(('allay', 'hex', 'amethyst')) else
              'prismarine_bricks' if name.startswith(('mana', 'prismarine', 'atomizer', 'trickster', 'kinetics')) else 'cut_copper')
    for x in range(9):
        for z in range(9):
            border = x in (0, 8) or z in (0, 8)
            material = 'polished_deepslate' if border else 'spruce_planks'
            if z == 6 and x in (2, 4, 6):
                material = accent
            put(x, 0, z, material)
    # Keep the entire front and the machine/pipe routes clear. The rear shelf is
    # intentionally low so it never hides the presses, spouts or their drives.
    put(0, 1, 8, 'polished_deepslate')
    put(0, 2, 8, 'amethyst_cluster', facing='up', waterlogged='false')
    put(2, 1, 8, 'spruce_slab', type='bottom', waterlogged='false')
    put(3, 1, 8, 'bookshelf')
    put(6, 1, 8, 'barrel', facing='up', open='false')
    put(6, 2, 8, 'lantern', hanging='false', waterlogged='false')
    put(8, 1, 8, accent)
    put(8, 2, 8, 'small_amethyst_bud', facing='up', waterlogged='false')
    # Ponder derives mutable-world bounds from placed blocks, not template.size.
    # An explicit air corner reserves headroom for machines built by instructions.
    put(8, 6, 8, 'air')
    root = {
        'DataVersion': (3, 3955),
        'size': (9, (3, [9, 7, 9])),
        'palette': (9, (10, palette)),
        'blocks': (9, (10, blocks)),
        'entities': (9, (10, [])),
    }
    raw = b'\x0a\x00\x00' + payload(10, root)
    return gzip.compress(raw, mtime=0)


def optional_guards():
    """Check that every optional storyboard is behind its dependency checks."""
    source = (JAVA / 'CMIPonderPlugin.java').read_text(encoding='utf-8')
    stack = []
    for line in source.splitlines():
        if line.strip().startswith('}') and stack:
            stack.pop()
        if '{' in line:
            stack.append(line)
        if 'add(helper' not in line:
            continue
        guards = '\n'.join(stack)
        if 'TricksterPonderScenes::' in line:
            assert 'isLoaded("trickster")' in guards and 'available(' in guards
        if 'HexPonderScenes::' in line:
            assert 'isLoaded("hexcasting")' in guards and 'available(' in guards
        if 'ChainPonderScenes::' in line:
            assert 'isLoaded("trickster")' in guards and 'isLoaded("bits_n_bobs")' in guards
    core = ['Workshop.java', 'AllayBurnerPonderScenes.java', 'MistPonderScenes.java', 'ProcessingPonderScenes.java', 'FuelTankPonderScenes.java']
    for name in core:
        code = (JAVA / name).read_text(encoding='utf-8')
        assert not re.search(r'import (?:dev.enjarai|at.petrak|com.kipti)', code), f'Optional type in core: {name}'


def recipe_contracts():
    """Fail if the real recipes no longer support the authored teaching examples."""
    recipes = ROOT / 'src/main/resources/data/createmanaindustry/recipe'
    def read(path):
        return json.loads((recipes / (path + '.json')).read_text(encoding='utf-8'))
    conversion = read('mixing/liquid_media_to_mana')
    assert conversion['heat_requirement'] == 'heated'
    assert conversion['ingredients'][0]['fluid'] == 'createmanaindustry:liquid_media'
    assert conversion['ingredients'][0]['amount'] == 125
    assert conversion['results'] == [{'id': 'createmanaindustry:liquid_mana', 'amount': 125}]
    glass = read('mist_mixing/glass_to_amethyst_block')
    assert glass['ingredients'].count({'item': 'minecraft:glass'}) == 32
    assert glass['ingredients'].count({'item': 'minecraft:iron_nugget'}) == 1
    assert glass['heat_requirement'] == 'allayheated'
    assert glass['mist_requirement']['fluid'] == 'createmanaindustry:liquid_media'
    assert glass['results'] == [{'id': 'minecraft:amethyst_block', 'count': 8}]
    assert read('compacting/ice_to_coolant')['results'] == [{'id': 'createmanaindustry:coolant', 'amount': 1000}]
    source = read('mixing/sweet_berries_to_source')
    assert source['ingredients'] == [{'item': 'minecraft:sweet_berries'}, {'type': 'neoforge:single', 'amount': 25, 'fluid': 'minecraft:water'}]
    assert source['results'] == [{'id': 'createmanaindustry:liquid_source', 'amount': 25}]
    for quartz in ('rose_quartz', 'prismarine_quartz'):
        melted = read('heated_compacting/molten_' + quartz)
        assert melted['heat_requirement'] == 'superheated'
        assert melted['results'] == [{'id': 'createmanaindustry:molten_' + quartz, 'amount': 250}]
        vapor = read('vaporizing/vaporizing_' + quartz)
        assert vapor['heat_requirement'] == 'allayheated'
        assert vapor['ingredients'][0]['amount'] == 125
        assert vapor['mist_result']['fluid'] == 'createmanaindustry:molten_' + quartz
    for coating, mist, heat in [('amethyst', 'liquid_media', None), ('rose_quartz', 'molten_rose_quartz', 'heated')]:
        for metal in ('iron', 'copper', 'golden', 'brass'):
            name = coating + '_deposited_' + metal + '_sheet'
            recipe = read('vapor_deposition/' + name)
            assert recipe['mist_requirement']['fluid'] == 'createmanaindustry:' + mist
            assert recipe.get('heat_requirement') == heat
            assert recipe['ingredients'] == [{'item': 'create:' + metal + '_sheet'}]
            assert recipe['results'] == [{'id': 'createmanaindustry:' + name, 'count': 1}]
    knot = read('deploying/trickster_emerald_knot')
    assert knot['ingredients'] == [{'item': 'minecraft:emerald'}, {'item': 'minecraft:glass'}]
    assert read('pressing/trickster_emerald_knot')['results'] == [{'id': 'trickster:cracked_emerald_knot'}]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--write', action='store_true')
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    catalog, registrations = scenes()
    optional_guards()
    recipe_contracts()
    expected = [{}, {}]
    for name, (en, zh, paragraphs, _) in catalog.items():
        for lang, title in enumerate((en, zh)):
            expected[lang][PREFIX + name + '.header'] = title
            for i, pair in enumerate(paragraphs, 1):
                expected[lang][PREFIX + name + '.text_' + str(i)] = pair[lang]
        dest = ASSETS / 'ponder/workshop' / (name + '.nbt')
        content = stage(name)
        if args.write:
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(content)
        assert dest.exists(), f'Missing structure: {name}'
        assert gzip.decompress(dest.read_bytes()) == gzip.decompress(content), f'Stale structure: {name}'
    for name, (en, zh, en_desc, zh_desc) in TAGS.items():
        expected[0][PREFIX + 'tag.' + name] = en
        expected[1][PREFIX + 'tag.' + name] = zh
        expected[0][PREFIX + 'tag.' + name + '.description'] = en_desc
        expected[1][PREFIX + 'tag.' + name + '.description'] = zh_desc
    for i, lang in enumerate(('en_us', 'zh_cn')):
        dest = ASSETS / 'lang' / (lang + '.json')
        data = json.loads(dest.read_text(encoding='utf-8'))
        if args.write:
            data = {k: v for k, v in data.items() if not k.startswith(PREFIX)}
            data.update(expected[i])
            dest.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        actual = {k: v for k, v in data.items() if k.startswith(PREFIX)}
        assert actual == expected[i], f'Missing, stale or misnumbered {lang} Ponder text; run --write'
    print(f'PASS: {len(catalog)} scenes, {len(registrations)} registrations, '
          f'{sum(len(v[2]) for v in catalog.values())} bilingual paragraphs, '
          f'{len(catalog)} deterministic NBT stages, optional dependency guards, recipe contracts')


if __name__ == '__main__':
    main()
