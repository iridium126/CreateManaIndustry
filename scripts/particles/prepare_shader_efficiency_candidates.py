"""Isolated candidates. No production source is edited; input manifest is mandatory."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil


def replace(path, old, new, count=None):
    text = path.read_text(encoding='utf8')
    actual = text.count(old)
    if not actual or (count is not None and actual != count):
        raise ValueError(f'{path}: expected {count} occurrences, found {actual}: {old}')
    path.write_text(text.replace(old, new), encoding='utf8')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('baseline', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--draw-mode', choices=('claim', 'carry'), default='claim')
    args = parser.parse_args()
    manifest = json.loads((args.baseline / 'manifest.json').read_text())
    for name, digest in manifest['files'].items():
        if hashlib.sha256((args.baseline / name).read_bytes()).hexdigest() != digest:
            raise ValueError('Baseline modified: ' + name)
    args.output.mkdir(parents=True, exist_ok=False)
    for variant in ('update', 'contacts', 'draw'):
        root = args.output / variant
        shutil.copytree(args.baseline / 'src/main/resources', root)
        shaders = root / 'assets/createmanaindustry/shaders/particles'
        if variant == 'update':
            replace(shaders / 'update.comp', '#pragma cmi_include chunks/allay_storm.glsl',
                    '#define CMI_READONLY_ATTACK_CACHE\n#pragma cmi_include chunks/allay_storm.glsl', 1)
            path = shaders / 'chunks/starfall_identity.glsl'
            replace(path, 'uint cmiStarAssignment(float identity){', '''#ifdef CMI_READONLY_ATTACK_CACHE
bool cmiAssignmentValid=false;uint cmiAssignmentMember=0u,cmiAssignmentValue=0u;
uint cmiStarAssignment(float identity){
 uint member=cmiStarMember(identity);if(!uStarfallEnabled||member>=131072u)return 0u;
 if(!cmiAssignmentValid||member!=cmiAssignmentMember){cmiAssignmentValue=cmiStarWord(16u+member);cmiAssignmentMember=member;cmiAssignmentValid=true;}
 return cmiAssignmentValue;
}
#else
uint cmiStarAssignment(float identity){''', 1)
            replace(path, 'uint cmiMajorRankFromKind', '#endif\nuint cmiMajorRankFromKind', 1)
            path = shaders / 'chunks/starfall.glsl'
            replace(path, 'uint cmiStarBase(uint slot)', '''#ifdef CMI_READONLY_ATTACK_CACHE
bool cmiKindValid=false;uint cmiKindBase=0u;float cmiKindValue=0.;
float cmiStarKind(uint b){if(!cmiKindValid||cmiKindBase!=b){cmiKindValue=cmiStarVec(b+20u).x;cmiKindBase=b;cmiKindValid=true;}return cmiKindValue;}
#else
float cmiStarKind(uint b){return cmiStarVec(b+20u).x;}
#endif
uint cmiStarBase(uint slot)''', 1)
            # Preserve cmiStarVec's raw-vector read and all exact-clock operations.
            for name in ('starfall.glsl', 'helix_rules.glsl', 'column_rules.glsl'):
                path = shaders / 'chunks' / name
                text = path.read_text(encoding='utf8')
                # Helper definitions must keep their original raw load.
                lines = text.splitlines(keepends=True)
                path.write_text(''.join(line if line.startswith('float cmiStarKind') else
                                        line.replace('cmiStarVec(b+20u).x', 'cmiStarKind(b)') for line in lines), encoding='utf8')
        elif variant == 'contacts':
            path = shaders / 'chunks/dome_contacts.glsl'
            replace(path, 'bool same=cmiDomeOwner(other.w)==owner',
                    'int otherOwner=cmiDomeOwner(other.w);bool same=otherOwner==owner', 2)
            replace(path, 'cmiDomeOwner(other.w)>=0', 'otherOwner>=0', 2)
        else:
            path = shaders / 'model.vsh'
            old = 'if(uStarfallEnabled&&!corpse&&stormA.x>.5){int claim=cmiStarClaim(p0.w,uTimeSec);if(claim>=0)carryRamp=cmiAttackCarry(p0.w,uTimeSec,p1.w);}'
            new = 'int cmiCarryClaim=-1;if(uStarfallEnabled&&!corpse&&stormA.x>.5){cmiCarryClaim=cmiStarClaim(p0.w,uTimeSec);if(cmiCarryClaim>=0)carryRamp=cmiAttackCarry(p0.w,uTimeSec,p1.w);}'
            compiler = root / 'ShaderPackProgramCompiler.java.txt'
            shutil.copyfile(args.baseline / 'src/main/java/com/iridium126/createmanaindustry/client/particles/shaderpack/ShaderPackProgramCompiler.java', compiler)
            for path in (path, compiler):
                if args.draw_mode == 'claim':
                    replace(path, old, new, 1)
                    replace(path, 'int k = cmiStormWaveClaim(p3.z,p0.w);', 'int k = uStarfallEnabled?cmiCarryClaim:cmiStormWaveClaim(p3.z,p0.w);', 1)
                    replace(path, 'carryRamp = uStarfallEnabled?cmiAttackCarry(p0.w,uTimeSec,p1.w):', 'carryRamp = uStarfallEnabled?carryRamp:', 1)
                else:
                    replace(path, 'carryRamp = uStarfallEnabled?cmiAttackCarry(p0.w,uTimeSec,p1.w):cmiStormCarryRamp(uTimeSec, uWave[k].z, uWave[k].w);',
                            'if(!uStarfallEnabled)carryRamp = cmiStormCarryRamp(uTimeSec, uWave[k].z, uWave[k].w);', 1)
    print('Prepared isolated update / contacts / draw candidates:', args.output)


if __name__ == '__main__':
    main()
