param([string]$OutputRoot='build/benchmarks/neighbor-r7-continuation-20261101')
$ErrorActionPreference='Stop';$failed=@()
function Run-Cell([string]$Name,[string[]]$Options){
    $destination=Join-Path $OutputRoot $Name
    if(Test-Path -LiteralPath $destination){throw "Refusing to overwrite $destination"}
    & .\gradlew.bat -I scripts/particles/validation.init.gradle @Options "-PbenchmarkOutput=$destination" --no-configuration-cache --console=plain *> (Join-Path $OutputRoot ($Name.Replace('/','-')+'.log'))
    if($LASTEXITCODE -ne 0){$script:failed+=$Name}
}
foreach($backend in @('cuda-control','rt-aabb')){Run-Cell "fixture-recheck/$backend" @('validateR7ContinuationGpu','-Pr7Counts=0,1,63,64,65,1000','-Pr7Modes=0','-Pr7Scenes=recovery',"-Pr7Backend=shared-$backend")}
foreach($backend in @('cuda-control','rt-aabb','rt-triangle','tensor','tensor-control')){Run-Cell "consumer-proof/$backend" @('validateR7ContinuationGpu','-Pr7Counts=65,1000','-Pr7Scenes=ordinary,dome_gather,dome_attack',"-Pr7Backend=shared-$backend")}
foreach($mutation in @('generation','overflow','incomplete','offset','id')){Run-Cell "result-rejection/$mutation" @('validateR7ContinuationGpu','-Pr7Counts=65','-Pr7Scenes=ordinary','-Pr7Backend=rt-aabb',"-Pr7Mutation=$mutation")}
Run-Cell 'safety-final-v2' @('validateR7AsyncGpu','validateR7CopyStateGpu','validateR7MediaGpu')
Run-Cell 'journal-final-v3' @('validateR7FullChainJournalGpu','validateR7ChainCaptureGpu','validateR7CaptureGpu')
foreach($round in 1..3){Run-Cell "copy-chain-r$round" @('benchmarkR7CopyChainGpu')}
Run-Cell 'draw-eight-packs' @('validateShaderEfficiencyGpu','-Pr7Draw')
Run-Cell 'draw-complementary-options' @('validateShaderEfficiencyGpu','-Pr7Draw','-PmodelDrawPack=complementary','-PdrawSnapshots=build/benchmarks/neighbor-r7/baseline/client/cmi-particle-profiles')
Run-Cell 'draw-native' @('validateModelNativeColorGpu','validateNativeAttachmentReplayGpu','-PnativeBaseline=src/main/resources','-PnativeCandidate=src/main/resources')
Run-Cell 'admission-sources' @('validateR7AdmissionSources')
if($failed.Count){throw "Failed final checks retained: $($failed -join ', ')"}
