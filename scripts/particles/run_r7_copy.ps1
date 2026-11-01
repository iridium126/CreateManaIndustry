param([Parameter(Mandatory=$true)][string]$InputFile,[string]$Output='build/benchmarks/neighbor-r7')
$ErrorActionPreference='Stop'
foreach($Round in 1..3){
 $Target="$Output/copy-concurrency-r$Round"
 if(Test-Path -LiteralPath $Target){throw "Refusing to overwrite $Target"}
 & .\gradlew.bat -I scripts/particles/validation.init.gradle validateR7CopyConcurrencyGpu "-Pr7CopyInput=$InputFile" "-PbenchmarkOutput=$Target" --no-configuration-cache --console=plain *> "$Output/copy-concurrency-r$Round.log"
 if($LASTEXITCODE -ne 0){throw "COPY concurrency process $Round failed"}
 Write-Output "R7 COPY independent process $Round completed"
}
