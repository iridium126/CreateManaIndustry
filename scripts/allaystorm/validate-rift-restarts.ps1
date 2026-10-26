param(
    [string[]]$Phases = @('PREPARING', 'SNAPSHOTTING', 'PREPARED', 'PARTIAL', 'COMMITTED', 'CHECKPOINTED'),
    [string]$RunPrefix = ('rift-restart-' + [guid]::NewGuid().ToString('N').Substring(0, 8)),
    [switch]$Sable
)
$ErrorActionPreference = 'Stop'
if ($RunPrefix -notmatch '^[a-zA-Z0-9_-]+$') { throw 'Invalid isolated run prefix' }
foreach ($phase in $Phases) {
    if ($phase -notin @('PREPARING','SNAPSHOTTING','PREPARED','PARTIAL','COMMITTED','CHECKPOINTED')) { throw "Unknown phase $phase" }
    $runName = $RunPrefix + '-' + $phase.ToLowerInvariant()
    $crashLog = 'build/' + $runName + '-crash.log'
    $recoverLog = 'build/' + $runName + '-recover.log'
    $vehicleArgs = @()
    if ($Sable) { $vehicleArgs = @('-PriftSable','-PriftFaultVehicle=sable') }
    & .\gradlew.bat -I scripts/allaystorm/rift-gametest.init.gradle runGameTestServer "-PriftRun=$runName" "-PriftFault=$phase" -PriftFaultMode=crash @vehicleArgs --offline --no-configuration-cache *> $crashLog
    if ($LASTEXITCODE -eq 0 -or !(Select-String -LiteralPath $crashLog -SimpleMatch "CMI_RIFT_EXPECTED_PROCESS_DEATH $phase" -Quiet)) { throw "Expected process-death marker missing: $crashLog" }
    & .\gradlew.bat -I scripts/allaystorm/rift-gametest.init.gradle runGameTestServer "-PriftRun=$runName" "-PriftFault=$phase" -PriftFaultMode=recover @vehicleArgs --offline --no-configuration-cache *> $recoverLog
    if ($LASTEXITCODE -ne 0) { throw "Restart recovery failed: $recoverLog" }
    Write-Output "PASS $phase : $recoverLog"
}
