param([int]$Samples=64,[int]$Queries=128,[int]$Rounds=3,[string]$Output='build/benchmarks/neighbor-r7/sequences')
$ErrorActionPreference='Stop'
if($Samples -lt 64 -or $Rounds -lt 3){throw 'Formal R7 runs require 64 pairs and three independent processes'}
foreach($Round in 1..$Rounds){
 foreach($InputName in @('motion','dome')){
  $Target="$Output/$InputName-r$Round"
  if(Test-Path -LiteralPath $Target){
   $Record=Join-Path $Target 'metadata.json'
   if(Test-Path -LiteralPath $Record){$Previous=Get-Content -LiteralPath $Record -Raw | ConvertFrom-Json;if($Previous.passed -and $Previous.samples -eq $Samples -and $Previous.queries_per_frame -eq $Queries){Write-Output "Preserving completed $Target";continue}}
   throw "Refusing to overwrite incomplete $Target"
  }
  $RunLog="$Output-$InputName-r$Round-$([guid]::NewGuid().ToString('N')).log"
  & .\gradlew.bat -I scripts/particles/validation.init.gradle validateR7SequenceGpu "-Pr7Inputs=build/benchmarks/neighbor-r7/inputs-$InputName" "-Pr7Samples=$Samples" "-Pr7Queries=$Queries" "-PbenchmarkOutput=$Target" --no-configuration-cache --console=plain *> $RunLog
  if($LASTEXITCODE -ne 0){throw "R7 $InputName round $Round failed; raw log preserved"}
  Write-Output "R7 $InputName independent process round $Round completed"
 }
}
