param([string]$Root='build/benchmarks/neighbor-r6/replay-formal')
$ErrorActionPreference='Stop'
if(Test-Path -LiteralPath $Root){throw 'Use a new output directory'}
New-Item -ItemType Directory -Path $Root | Out-Null
foreach($Round in 1..3){
    foreach($Variant in @('AA','complete','prune','grid250','grid250wide','grid125','grid100','order-space','order-phase')){
        $Output=Join-Path $Root "$Variant-$Round"
        $Arguments=@('-I','scripts/particles/validation.init.gradle','validateR6CaptureReplayGpu','-Pr6Candidates=build/benchmarks/neighbor-r6/candidates-v4',"-Pr6Variant=$(if($Variant -eq 'AA'){'production'}else{$Variant})",'-Pr6Samples=64',"-PbenchmarkOutput=$Output",'--no-configuration-cache','--console=plain')
        if($Variant -eq 'AA'){$Arguments+='-Pr6Aa=true'}
        & .\gradlew.bat @Arguments *> "$Output.log"
        # An isolated candidate failure is evidence, not a reason to skip later candidates.
        Add-Content -LiteralPath (Join-Path $Root 'processes.csv') -Value "$Round,$Variant,$LASTEXITCODE"
    }
}
