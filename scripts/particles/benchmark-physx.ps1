param(
    [int[]]$Counts = @(10000,65536,131072),
    [int[]]$Frequencies = @(20,30,60),
    [int[]]$Iterations = @(4),
    [ValidateSet('gpu','cpu')][string[]]$Backends = @('gpu','cpu'),
    [ValidateSet('tgs','pgs')][string]$Solver = 'tgs',
    [int]$Warmup = 50, [int]$Samples = 40, [int]$Trials = 3,
    [string]$Output = 'build/package-backend-physx'
)
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$directory = [IO.Path]::GetFullPath((Join-Path $taskRoot $Output))
New-Item -ItemType Directory -Path $directory -Force | Out-Null
# This standalone process is the only place that may block on PhysX/CUDA.
# Run it separately from the game and from compute benchmarks.
& (Join-Path $PSScriptRoot 'build-physx.ps1') *> (Join-Path $directory 'build.log')
$executable = Join-Path $taskRoot '.gradle/physx/build-ovphysx-0.6.3/CmiPackagePhysxBenchmark.exe'
$manifest = @()
foreach ($count in $Counts) { foreach ($hz in $Frequencies) { foreach ($iterationCount in $Iterations) { foreach ($backend in $Backends) {
    $name = "$backend-$Solver-$count-$hz-$iterationCount"
    $csv = Join-Path $directory "$name.csv"
    $log = Join-Path $directory "$name.log"
    & $executable --backend $backend --solver $Solver --count $count --hz $hz --iterations $iterationCount --warm $Warmup --samples $Samples --trials $Trials --output $csv *> $log
    $nativeExit = $LASTEXITCODE
    # Keep overflow/crash outcomes alongside successful rows. Never quietly
    # substitute CPU or turn an aborted run into a faster valid measurement.
    $manifest += [pscustomobject]@{backend=$backend; solver=$Solver; count=$count; hz=$hz; iterations=$iterationCount; exit_code=$nativeExit; csv=$name+'.csv'; log=$name+'.log'}
    $manifest | Export-Csv -LiteralPath (Join-Path $directory 'manifest.csv') -NoTypeInformation
    Write-Output "$name exit=$nativeExit"
    Get-Content -LiteralPath $log -Tail 1
} } } }
