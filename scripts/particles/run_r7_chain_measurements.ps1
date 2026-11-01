param([string]$OutputRoot='build/benchmarks/neighbor-r7-continuation-20261101/chain-measurements-v2')
$ErrorActionPreference='Stop'
if(Test-Path -LiteralPath $OutputRoot){throw "Refusing to overwrite $OutputRoot"}
New-Item -ItemType Directory -Path $OutputRoot | Out-Null
$failed=@()
foreach($round in 1..3){foreach($backend in @('cuda-control','rt-aabb','rt-triangle','tensor','tensor-control')){
    $destination=Join-Path $OutputRoot "r$round-$backend"
    & .\gradlew.bat -I scripts/particles/validation.init.gradle validateR7ContinuationGpu '-Pr7Counts=1000,10000,65536,131072' -Pr7Modes=1 '-Pr7Scenes=ordinary,dome_attack' "-Pr7Backend=shared-$backend" -Pr7SlotBytes=16777216 -Pr7Measure=true -Pr7Layout=paired-islands "-PbenchmarkOutput=$destination" --no-configuration-cache --console=plain *> "$destination.log"
    if($LASTEXITCODE -ne 0){$failed+="r$round-$backend"}
}}
if($failed.Count){throw "Failed measurements retained: $($failed -join ', ')"}
