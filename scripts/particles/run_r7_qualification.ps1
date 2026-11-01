param([string]$OutputRoot='build/benchmarks/neighbor-r7-continuation-20261101/qualification-v1')
$ErrorActionPreference='Stop'
if(Test-Path -LiteralPath $OutputRoot){throw "Refusing to overwrite $OutputRoot"}
New-Item -ItemType Directory -Path $OutputRoot | Out-Null
$failed=@()
function Run-Cell([string]$Name,[string[]]$Options){
    $destination=Join-Path $OutputRoot $Name
    & .\gradlew.bat -I scripts/particles/validation.init.gradle @Options "-PbenchmarkOutput=$destination" --no-configuration-cache --console=plain *> "$destination.log"
    if($LASTEXITCODE -ne 0){$script:failed+=$Name}
}
Run-Cell 'query-trace' @('validateR7CaptureGpu')
foreach($backend in @('cuda-control','rt-aabb','rt-triangle','tensor','tensor-control')){
    if($backend -ne 'rt-aabb'){
        Run-Cell "multiframe-$backend" @('validateR7ContinuationGpu','-Pr7Counts=65','-Pr7Scenes=ordinary,corpse,wrap,column,recovery,dome_gather,dome_attack','-Pr7Frames=96',"-Pr7Backend=shared-$backend")
    }
    Run-Cell "matrix-$backend" @('validateR7ContinuationGpu','-Pr7Counts=0,1,63,64,65,1000','-Pr7Modes=0,1,2','-Pr7Scenes=ordinary,starfall,helix,column,recovery,corpse,wrap,missing_collision,dome_gather,dome_attack,crowded,boundary,irregular',"-Pr7Backend=shared-$backend")
    Run-Cell "large-$backend" @('validateR7ContinuationGpu','-Pr7Counts=10000,50000,65535,65536,131072','-Pr7Scenes=ordinary,dome_attack',"-Pr7Backend=shared-$backend")
    Run-Cell "capacity-$backend" @('validateR7ContinuationGpu','-Pr7Counts=1000','-Pr7Scenes=crowded','-Pr7SlotBytes=2097152',"-Pr7Backend=shared-$backend")
}
if($failed.Count){throw "Failed candidates retained: $($failed -join ', ')"}
