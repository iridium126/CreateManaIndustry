"""Archive first-party evidence and an installable bootstrap JAR without third-party pack sources."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import shutil
import zipfile


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('results', type=Path)
    parser.add_argument('jar', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    jar = args.output/'createmanaindustry-0.2.7-shader-diagnostics-r4-20261031.jar'
    archive = args.output/'shader-efficiency-r4-results-20261031.zip'
    if jar.exists() or archive.exists():
        raise FileExistsError('Refusing to replace earlier deliverables')
    manifest = json.loads((args.results/'baseline/manifest.json').read_text())
    for name, digest in manifest['files'].items():
        if sha(args.results/'baseline'/name) != digest:
            raise ValueError('Frozen input changed: '+name)
    with zipfile.ZipFile(args.jar) as outer:
        with zipfile.ZipFile(io.BytesIO(outer.read('META-INF/jars/createmanaindustry.jar'))) as inner:
            names = inner.namelist()
            if not any(n.endswith('/ParticleProfileSnapshot.class') for n in names):
                raise ValueError('Missing production diagnostics')
            if any('ReadonlyBaseline' in n or 'ReadonlyCandidate' in n or 'GpuValidation' in n for n in names):
                raise ValueError('Validation-only class leaked into distribution')
            resources = 'src/main/resources/'
            compared = 0
            for name, digest in manifest['files'].items():
                if name.startswith(resources+'assets/createmanaindustry/shaders/'):
                    if hashlib.sha256(inner.read(name[len(resources):])).hexdigest() != digest:
                        raise ValueError('An experimental shader reached the production JAR: '+name)
                    compared += 1
    shutil.copyfile(args.jar, jar)
    files = {}
    with zipfile.ZipFile(archive, 'x', compression=zipfile.ZIP_DEFLATED) as bundle:
        for source in sorted(args.results.rglob('*')):
            if not source.is_file():
                continue
            relative = source.relative_to(args.results)
            # Extracted pack .glsl/.vsh/.fsh, images and archives are deliberately omitted.
            if relative.parts[0] in ('baseline', 'candidates', 'candidates-carry'):
                include = (relative.parts[0] == 'baseline'
                           or relative.parts[0] != 'baseline' and source.suffix in ('.comp', '.glsl', '.vsh', '.fsh', '.txt'))
            else:
                include = source.suffix in ('.csv', '.json', '.log', '.txt')
            if include:
                name = 'results/'+relative.as_posix()
                bundle.write(source, name)
                files[name] = sha(source)
        for directory in ('scripts/particles', 'src/main/java/com/iridium126/createmanaindustry/client/particles/engine',
                          'src/main/java/com/iridium126/createmanaindustry/client/particles/shaderpack',
                          'src/main/java/com/iridium126/createmanaindustry/client/render/shaderpack'):
            for source in sorted(Path(directory).glob('*')):
                if source.is_file() and source.suffix in ('.py', '.java', '.gradle'):
                    bundle.write(source, 'reproduction/'+source.as_posix())
        report = Path('docs/benchmarks/shader-efficiency-r4-2026-10-31.md')
        bundle.write(report, 'report.md')
        for source in Path('build/test-results/test').glob('TEST-*.xml'):
            bundle.write(source, 'junit/'+source.name)
        provenance = dict(jar=jar.name, jar_sha256=sha(jar), unchanged_production_shader_files=compared,
                          baseline_manifest_sha256=sha(args.results/'baseline/manifest.json'),
                          runtime_shader_optimization_adopted=False, files=files)
        bundle.writestr('provenance.json', json.dumps(provenance, indent=2)+'\n')
    print(json.dumps(dict(jar=str(jar), jar_sha256=sha(jar), archive=str(archive), archive_sha256=sha(archive),
                          unchanged_shader_files=compared), indent=2))


if __name__ == '__main__':
    main()
