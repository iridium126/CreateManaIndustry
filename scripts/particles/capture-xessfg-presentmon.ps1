[CmdletBinding()]
param(
    [int]$GamePid,
    [ValidateRange(10, 600)][int]$Seconds = 30,
    [string]$GameDirectory = '',
    [string]$PresentMonExe = '',
    [string]$FrameCapture = '',
    [string]$PresentMonCsv = ''
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
if (!$GameDirectory) { $GameDirectory = Join-Path $root 'run' }
if (!$PresentMonExe) { $PresentMonExe = Join-Path $root 'run/.cache/presentmon/PresentMon-2.6.0-x64.exe' }
$merge = Join-Path $PSScriptRoot 'merge-xessfg-presentmon.py'

if ($FrameCapture -or $PresentMonCsv) {
    if (!$FrameCapture -or !$PresentMonCsv) { throw 'Pass both -FrameCapture (JSON) and -PresentMonCsv to merge existing captures.' }
    python $merge $FrameCapture $PresentMonCsv
    exit $LASTEXITCODE
}

if (!(Test-Path -LiteralPath $PresentMonExe -PathType Leaf)) {
    throw "PresentMon console was not found at $PresentMonExe. Use -PresentMonExe to specify its path."
}
if (!$GamePid) {
    $clients = @(Get-Process -Name java,javaw -ErrorAction SilentlyContinue |
        Where-Object { $_.MainWindowHandle -ne 0 -and $_.MainWindowTitle -match 'Minecraft' })
    if ($clients.Count -ne 1) { throw 'Start Minecraft and make its window visible, or pass its process ID with -GamePid.' }
    $GamePid = $clients[0].Id
}
Get-Process -Id $GamePid -ErrorAction Stop | Out-Null

$folder = Join-Path $GameDirectory 'logs/cmi-particle-frames'
New-Item -ItemType Directory -Path $folder -Force | Out-Null
$started = [DateTime]::UtcNow
$stamp = $started.ToString('yyyyMMdd-HHmmss-fff')
$csv = Join-Path $folder "presentmon-$stamp-$GamePid.csv"
Write-Host "Recording Minecraft PID $GamePid for $Seconds seconds."
Write-Host 'In Minecraft, run /cmi particle profile frames 10 now, then close chat.'
& $PresentMonExe --process_id $GamePid --session_name "CMI-XeSSFG-$stamp-$GamePid" --qpc_time `
    --track_frame_type --track_app_timing --write_frame_id --no_track_input --no_console_stats `
    --timed $Seconds --terminate_after_timed --output_file $csv
if ($LASTEXITCODE -ne 0) {
    throw "PresentMon exited with code $LASTEXITCODE. If Windows denied ETW access, run this script in an elevated PowerShell."
}
if (!(Test-Path -LiteralPath $csv -PathType Leaf)) {
    throw 'PresentMon produced no CSV. Check the selected Minecraft PID and the ETW messages above.'
}
Write-Host "PresentMon: $csv"

$capture = Get-ChildItem -LiteralPath $folder -File -Filter 'frames-*.json' |
    Sort-Object LastWriteTimeUtc -Descending |
    Where-Object {
        if ($_.LastWriteTimeUtc -lt $started) { return $false }
        $metadata = Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json
        return $metadata.process_id -eq $GamePid -and $metadata.xessfg_csv_schema -ge 3 -and
            !$metadata.invalid_reason -and ([DateTimeOffset]$metadata.started).UtcDateTime -ge $started
    } | Select-Object -First 1
if (!$capture) {
    Write-Warning 'No complete frame capture from this run. The PresentMon CSV can be merged later using -FrameCapture and -PresentMonCsv.'
    exit 0
}
if (!(Get-Command python -ErrorAction SilentlyContinue)) { throw 'Python 3 is required to merge the saved CSV files.' }
python $merge $capture.FullName $csv
exit $LASTEXITCODE
