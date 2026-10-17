"""Check the built distribution, including nested main jars, for moved classes and leaked fixtures."""
import argparse
import io
import json
import re
from pathlib import Path
import zipfile


def entries(path):
    result=[]
    def visit(data,prefix):
        with zipfile.ZipFile(data) as jar:
            for name in jar.namelist():
                result.append((prefix,name))
                if name.endswith('.jar'):
                    visit(io.BytesIO(jar.read(name)),prefix+'!/'+name)
    visit(path,str(path))
    return result


def main():
    parser=argparse.ArgumentParser(__doc__)
    parser.add_argument('jar',type=Path)
    args=parser.parse_args()
    records=entries(args.jar)
    repository=Path(__file__).resolve().parents[2]
    fixtures=set()
    for source_root in (repository/'src/test',repository/'scripts/particles'):
        for source in source_root.rglob('*.java'):
            text=source.read_text(encoding='utf-8')
            package=re.search(r'^package\s+([^;]+);',text,re.M)
            name=(package[1].replace('.','/')+'/' if package else '')+source.stem
            if not (repository/'src/main/java'/(name+'.java')).exists():fixtures.add(name)
    root='com/iridium126/createmanaindustry/'
    own=[(archive,name) for archive,name in records if name.startswith(root) and name.endswith('.class')]
    forbidden=[(archive,name) for archive,name in records if name.endswith('.class') and ('/gametest/' in name.lower() and name.startswith(root)
               or name.removesuffix('.class').split('$',1)[0] in fixtures
               or name.startswith(root+'client/particles/packages/')
               or name.endswith('/StormParticleAccess.class'))]
    names={name for _,name in own}
    required=[root+'client/render/scene/ClientGpuScene.class',root+'client/render/scene/ParticleRenderer.class',
              root+'client/particles/engine/CMIParticleEngine.class',root+'client/particles/interaction/ModelInteractionRuntime.class',
              root+'client/allaystorm/StormFeedback.class',root+'client/packages/render/PackageRuntime.class']
    missing=[name for name in required if name not in names]
    print(json.dumps({'jar':str(args.jar),'ownClasses':len(own),'newPackageClasses':sum('/client/packages/' in name for name in names),
                      'leakedOrRetiredClasses':forbidden,'missingOwners':missing},ensure_ascii=False,indent=2))
    if forbidden or missing:raise SystemExit(1)


if __name__=='__main__':
    main()
