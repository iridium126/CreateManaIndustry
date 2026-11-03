"""Sequential offscreen regressions; never launches the Minecraft client."""
import argparse
import json
import subprocess
from pathlib import Path
from run_r9 import ROOT, CAPTURE, context
from summarize_r9 import summarize


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--suite', choices=('checks', 'rules', 'measure', 'all'), default='all')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    frozen = context()
    jobs = []

    def run(name, task, **options):
        command = ['cmd', '/d', '/c', str(ROOT / 'gradlew.bat'), '--offline',
                   '--no-configuration-cache', '--console=plain', '-I',
                   'scripts/particles/validation.init.gradle', task,
                   '-PbenchmarkOutput=' + str(args.output / name), '-Pr7Capture=' + str(CAPTURE)]
        command += ['-P' + key + '=' + str(value) for key, value in options.items()]
        before = context()
        with (args.output / (name + '.log')).open('wb') as log:
            result = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
        after = context()
        jobs.append(dict(name=name, arguments=command, exit_code=result.returncode,
                         before=before, after=after))
        (args.output / 'jobs.json').write_text(json.dumps(jobs, indent=2) + '\n')
        if result.returncode or before != after or before != frozen:
            raise SystemExit('Failed job retained: ' + name)
        for report in (args.output / name).glob('*.json'):
            data = json.loads(report.read_text())
            if isinstance(data, dict) and (data.get('passed') is False or data.get('failures')):
                raise SystemExit('Failed GPU report: ' + str(report))
        print(name + ' PASS', flush=True)

    if args.suite in ('checks', 'all'):
        for name, task, options in (
                ('forces', 'validateR9HardwareGpu', {}),
                ('async', 'validateR7AsyncGpu', {'r8Policy': 2}),
                ('avoidance', 'validateAvoidanceGpu', {}),
                ('local', 'validateAvoidanceLocalGpu', {}),
                ('stall', 'validateDomeStallGpu', {}),
                ('real-faults', 'validateR9RealChainGpu', {}),
                ('admission', 'validateR7AdmissionSources', {})):
            run(name, task, **options)
    if args.suite in ('rules', 'all'):
        for policy in (83, 211, 339):
            run('rules-p' + str(policy), 'validateR7ContinuationGpu',
                r7Backend='shared-rt-aabb', r8Policy=policy, r7Counts='0,1,63,64,65,1000',
                r7Scenes='ordinary,starfall,column,dome_gather,dome_attack,corpse,recovery,missing_collision',
                r7Modes='1,2', r7Frames=10)
        # Keep production atomic-order A/A differences visible; they do not prove equivalence.
        unresolved = {job['name']: json.loads((args.output / job['name'] / 'continuation.json').read_text())
                      .get('reference_aa_differences', []) for job in jobs if job['name'].startswith('rules-')}
        (args.output / 'rule-oracle-limitations.json').write_text(json.dumps(unresolved, indent=2) + '\n')
    if args.suite in ('measure', 'all'):
        measurement = args.output / 'measure-final'
        measurement.mkdir()
        for index in (1, 2, 3):
            run('measure-final/actual-r' + str(index), 'validateR9RealChainGpu', r9Measure='true')
        measured = [dict(job, name=job['name'].split('/')[-1]) for job in jobs
                    if job['name'].startswith('measure-final/')]
        (measurement / 'jobs.json').write_text(json.dumps(measured, indent=2) + '\n')
        result = summarize(args.output)
        (args.output / 'measurement-summary.json').write_text(json.dumps(result, indent=2) + '\n')


if __name__ == '__main__':
    main()
