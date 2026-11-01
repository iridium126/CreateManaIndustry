param([string]$OutputRoot='build/benchmarks/neighbor-r7-continuation-20261101/remaining-backends-v1')
$ErrorActionPreference='Stop'
if(Test-Path -LiteralPath $OutputRoot){throw "Refusing to overwrite $OutputRoot"}
New-Item -ItemType Directory -Path $OutputRoot | Out-Null
$failed=@()
foreach($backend in @('cuda-control','rt-triangle','tensor','tensor-control')){
    $destination=Join-Path $OutputRoot $backend
    & .\gradlew.bat -I scripts/particles/validation.init.gradle validateR7ContinuationGpu '-Pr7Counts=0,1,63,64,65,1000' "-Pr7Backend=shared-$backend" "-PbenchmarkOutput=$destination" --no-configuration-cache --console=plain *> "$destination.log"
    if($LASTEXITCODE -ne 0){$failed+=$backend}
}
if($failed.Count){throw "Failed candidates retained: $($failed -join ', ')"}
