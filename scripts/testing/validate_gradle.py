"""Exercise the shared Gradle scripts in tiny offline projects, without Minecraft or cache mutation."""
import json
from pathlib import Path
import subprocess
import sys
import uuid
import zipfile


ROOT = Path(__file__).resolve().parents[2]
WRAPPER = ROOT / ('gradlew.bat' if sys.platform == 'win32' else 'gradlew')


def run(project, *arguments, success=True):
    command = [str(WRAPPER), '-p', str(project), *arguments, '--offline', '--console=plain']
    result = subprocess.run(command, cwd=ROOT, text=True, encoding='utf-8', errors='replace',
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    logs = list(project.glob('run-*.log'))
    (project / f'run-{len(logs) + 1}.log').write_text(result.stdout, encoding='utf-8')
    if (result.returncode == 0) != success:
        raise AssertionError(f'{command}\n{result.stdout}')
    return result.stdout


def fixture(directory):
    directory.mkdir(parents=True)
    (directory / 'gradle.properties').write_text(
        'trickster_project_id=123\ntrickster_file_id=456\nfabric_api_version=test\n', encoding='utf-8')
    return directory


def remap_cases(output):
    script = (ROOT / 'gradle/remapped-libs.gradle').as_posix()
    cases = [('complete', True, True, None), ('missing-trickster', False, True, None),
             ('missing-fabric', True, False, None), ('missing-both', False, False, None),
             ('regenerate', True, True, 'true'), ('regenerate-presence', True, True, 'false')]
    for name, trickster, fabric, regenerate in cases:
        project = fixture(output / name)
        cache = project / 'loom-remap/remapped-libs'
        cache.mkdir(parents=True)
        if trickster:
            (cache / 'trickster-123-456-named.jar').write_bytes(b'fixture')
        if fabric:
            (cache / 'fabric-api-test-named.jar').write_bytes(b'fixture')
        expected = trickster and fabric and regenerate is None
        (project / 'settings.gradle').write_text(f"""
rootProject.name = 'remap-contract'
apply from: '{script}'
def selection = gradle.ext.cmiRemappedLibraries
assert selection.useCache == {str(expected).lower()}
assert (findProject(':loom-remap') != null) == {str(not expected).lower()}
assert selection.artifacts.values().every {{ it.parentFile == new File(settingsDir, 'loom-remap/remapped-libs') }}
""", encoding='utf-8')
        flags = [f'-PregenerateRemap={regenerate}'] if regenerate is not None else []
        run(project, 'help', '--no-configuration-cache', *flags)
        print(f'PASS remap/{name}', flush=True)

    # The real settings cache must invalidate when either previously present JAR disappears.
    project = fixture(output / 'remap-cache-invalidation')
    cache = project / 'loom-remap/remapped-libs'
    cache.mkdir(parents=True)
    (cache / 'trickster-123-456-named.jar').write_bytes(b'fixture')
    fabric = cache / 'fabric-api-test-named.jar'
    fabric.write_bytes(b'fixture')
    (project / 'settings.gradle').write_text(f"""
rootProject.name = 'remap-cache-invalidation'
apply from: '{script}'
println('REMAP_CACHE=' + gradle.ext.cmiRemappedLibraries.useCache)
""", encoding='utf-8')
    assert 'REMAP_CACHE=true' in run(project, 'help', '--configuration-cache')
    fabric.unlink()
    assert 'REMAP_CACHE=false' in run(project, 'help', '--configuration-cache')
    print('PASS remap/configuration-cache-invalidation', flush=True)


ENTRIES = {
    'META-INF/jarjar/net.caffeinemc.sodium-fixture-mod.jar': ('sodium-core.jar', b'sodium-fixture'),
    'META-INF/jarjar/sable-companion-common-fixture.jar': ('sable-companion.jar', b'sable-fixture'),
    'META-INF/jars/cardinal-components-base-fixture.jar': ('cardinal-components-base.jar', b'cca-fixture'),
    'META-INF/jars/netty-fixture.jar': ('endec-netty.jar', b'endec-fixture'),
}


def write_archive(path, suffix=b'', duplicate=False, missing=False):
    with zipfile.ZipFile(path, 'w') as archive:
        for entry, (_, data) in ENTRIES.items():
            if missing and 'net.caffeinemc.sodium-' in entry:
                continue
            archive.writestr(entry, data + suffix)
        if duplicate:
            archive.writestr('META-INF/jarjar/net.caffeinemc.sodium-second-mod.jar', b'ambiguous')


def extraction_cases(output):
    project = fixture(output / 'nested-apis')
    (project / 'settings.gradle').write_text("rootProject.name = 'nested-api-contract'\n", encoding='utf-8')
    script = (ROOT / 'gradle/optional-apis.gradle').as_posix()
    (project / 'build.gradle').write_text(f"""
plugins {{ id 'java' }}
def forbid = providers.gradleProperty('forbidResolve').isPresent()
configurations.configureEach {{ incoming.beforeResolve {{
    if (forbid) throw new GradleException('Dependency resolved by an unrelated task')
}} }}
ext.cmiCoordinates = [sodium: files('distribution.jar'), sable: files('distribution.jar'),
                      cardinalComponents: files('distribution.jar'), owo: files('distribution.jar')]
apply from: '{script}'
""", encoding='utf-8')
    archive = project / 'distribution.jar'
    write_archive(archive)
    run(project, 'help', '-PforbidResolve', '--no-configuration-cache')
    print('PASS nested-apis/lazy-help', flush=True)
    tasks = ['extractSodiumCoreJar', 'extractSableCompanion', 'extractCardinalBase', 'extractEndecNetty']
    run(project, *tasks, '--configuration-cache')
    for _, (name, data) in ENTRIES.items():
        assert (project / '.gradle/cmi/optional-apis' / name).read_bytes() == data
    reused = run(project, *tasks, '--configuration-cache')
    assert 'Reusing configuration cache.' in reused and reused.count('UP-TO-DATE') == 4
    print('PASS nested-apis/exact-copy-and-cache-reuse', flush=True)
    write_archive(archive, suffix=b'-updated')
    run(project, *tasks, '--configuration-cache')
    for _, (name, data) in ENTRIES.items():
        assert (project / '.gradle/cmi/optional-apis' / name).read_bytes() == data + b'-updated'
    print('PASS nested-apis/version-refresh', flush=True)
    preserved = (project / '.gradle/cmi/optional-apis/sodium-core.jar').read_bytes()
    for name, options in [('ambiguous', dict(duplicate=True)), ('missing', dict(missing=True))]:
        write_archive(archive, **options)
        error = run(project, 'extractSodiumCoreJar', '--configuration-cache', success=False)
        assert 'Expected exactly one nested API' in error
        assert (project / '.gradle/cmi/optional-apis/sodium-core.jar').read_bytes() == preserved
        print(f'PASS nested-apis/{name}-preserves-output', flush=True)


def voxy_cases(output):
    script = (ROOT / 'gradle/voxy.gradle').as_posix()
    for cached in [True, False]:
        name = 'cached' if cached else 'missing'
        project = fixture(output / f'voxy-{name}')
        (project / 'settings.gradle').write_text("rootProject.name = 'voxy-contract'\n", encoding='utf-8')
        (project / 'build.gradle').write_text(f"plugins {{ id 'java' }}\napply from: '{script}'\n", encoding='utf-8')
        jar = project / '.gradle/voxy/artifacts/voxy-0.2.15-beta+1.21.1-neoforge.jar'
        if cached:
            jar.parent.mkdir(parents=True)
            jar.write_bytes(b'cached-voxy')
            result = run(project, 'cacheVoxy', '--configuration-cache')
            assert result.count('SKIPPED') == 5 and not (project / '.gradle/voxy/source').exists()
            assert 'Reusing configuration cache.' in run(project, 'cacheVoxy', '--configuration-cache')
            assert jar.read_bytes() == b'cached-voxy'
        else:
            result = run(project, 'cacheVoxy', '--configuration-cache', success=False)
            assert 'Voxy cache/source is missing' in result
            # Gradle creates declared output directories even when doFirst rejects offline work.
            source = project / '.gradle/voxy/source'
            assert not source.exists() or not any(source.iterdir())
            assert 'Voxy cache/source is missing' in run(project, 'cacheVoxy', '--configuration-cache', success=False)
        print(f'PASS voxy/offline-{name}', flush=True)


def main():
    output = ROOT / 'build/gradle-validation' / uuid.uuid4().hex[:12]
    remap_cases(output)
    extraction_cases(output)
    voxy_cases(output)
    (output / 'result.json').write_text(json.dumps(dict(passed=True, scenarios=14)) + '\n', encoding='utf-8')
    print(f'Gradle contracts passed. Evidence: {output}', flush=True)


if __name__ == '__main__':
    main()
