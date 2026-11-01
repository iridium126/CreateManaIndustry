"""Three independent paired A/B and A/A processes; a screen never authorizes replacement."""
import argparse
import csv
import json
from pathlib import Path
import subprocess

import summarize_model_draw as draw
from summarize_update_efficiency import changes


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('baseline', type=Path)
    parser.add_argument('candidate', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    runs = {'ab': [], 'aa': []}
    for round_index in range(3):
        for variant in (('ab', 'aa') if round_index % 2 == 0 else ('aa', 'ab')):
            directory = args.output/f'{variant}-{round_index}'
            command = [str(Path('gradlew.bat').resolve()), '--offline', '--no-configuration-cache', '-I',
                       'scripts/particles/validation.init.gradle', 'benchmarkShaderEfficiencyGpu', '-PreadonlyDraw',
                       f'-PreadonlyBaseline={args.baseline}', f'-PreadonlyCandidate={args.candidate}',
                       '-PmodelDrawPack=complementary,solas', '-PmodelDrawQuick', '-PmodelLoadRounds=1',
                       '-PdrawRounds=1', '-PdrawSamples=64', f'-PbenchmarkOutput={directory}']
            if variant == 'aa':
                command.append('-PmodelDrawVariant=aa')
            print(f'Starting independent {variant} process {round_index}', flush=True)
            with (args.output/f'{variant}-{round_index}.log').open('w', encoding='utf8') as stream:
                result = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT)
            if result.returncode:
                (args.output/'summary.json').write_text(json.dumps(dict(passed=False, failed_process=str(directory)), indent=2))
                raise SystemExit(result.returncode)
            groups = draw.groups(draw.read(directory/'production.csv'))
            if len(groups) != 12 or any(len(rows) != 64 or sorted(int(r['sample']) for r in rows) != list(range(64)) for rows in groups.values()):
                raise ValueError('Incomplete two-pack screen: '+str(directory))
            for row in draw.read(directory/'coverage.csv'):
                if row['baseline_covered'] != str(int(row['scene']) != 2).lower() or row['candidate_covered'] != row['baseline_covered']:
                    raise ValueError('Invalid coverage')
            runs[variant].append(groups)
    expected = set(runs['ab'][0])
    if any(set(groups) != expected for groups in runs['ab']+runs['aa']):
        raise ValueError('Mismatched independent process inputs')
    metrics, improved, regressed = [], [], []
    for key in sorted(expected):
        controls = [changes(groups[key]) for groups in runs['aa']]
        rounds = [changes(groups[key]) for groups in runs['ab']]
        nr = max(.005, *(abs(c['relative']) for c in controls))
        na = max(.002, *(abs(c['absolute']) for c in controls))
        pr = max(.005, *(abs(c['p95_relative']) for c in controls))
        pa = max(.002, *(abs(c['p95_absolute']) for c in controls))
        if all(r['relative'] < -nr and r['absolute'] < -na for r in rounds):
            improved.append(key)
        if (all(r['relative'] > nr and r['absolute'] > na for r in rounds)
                or all(r['p95_relative'] > pr and r['p95_absolute'] > pa for r in rounds)):
            regressed.append(key)
        metrics.append(dict(cell=key, rounds=rounds, noise_relative=nr, noise_ms=na,
                            p95_noise_relative=pr, p95_noise_ms=pa))
    result = dict(passed=False, screen_promising=bool(improved) and not regressed, improvements=improved,
                  regressions=regressed, metrics=metrics,
                  limits='Screen only: two packs / 1080p / 50000 members / synthetic FP32 MRT. Full native-format matrix and matching client options required before replacement.')
    (args.output/'summary.json').write_text(json.dumps(result, indent=2)+'\n', encoding='utf8')
    print('Screen:',len(improved),'stable improvements;',len(regressed),'stable regressions',flush=True)


if __name__ == '__main__':
    main()
