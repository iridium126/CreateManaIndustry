[CmdletBinding()]
param(
    [int]$GamePid,
    [ValidateRange(10, 600)][int]$Seconds = 30,
    [string]$GameDirectory = '',
    [string]$PresentMonExe = '',
    [string]$FrameCapture = '',
    [string]$PresentMonCsv = '',
    [ValidateRange(1, 10000)][double]$RefreshHz,
    [switch]$TracePresent,
    [string]$WprExe = '',
    [ValidatePattern('^\d{8}-\d{6}-\d{3}-[1-9]\d*$')][string[]]$RecoverTrace
)

$ErrorActionPreference = 'Stop'
# Handle recorder exit codes explicitly even when the caller enabled this PowerShell 7 option.
$PSNativeCommandUseErrorActionPreference = $false
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
if (!$GameDirectory) { $GameDirectory = Join-Path $root 'run' }
if (!$PresentMonExe) { $PresentMonExe = Join-Path $root 'run/.cache/presentmon/PresentMon-2.6.0-x64.exe' }
$merge = Join-Path $PSScriptRoot 'merge-xessfg-presentmon.py'
$pacingArgs = @()
if ($RefreshHz) {
    $pacingArgs = @('--refresh-hz', $RefreshHz.ToString([Globalization.CultureInfo]::InvariantCulture))
}

if ($FrameCapture -or $PresentMonCsv) {
    if (!$FrameCapture -or !$PresentMonCsv) { throw 'Pass both -FrameCapture (JSON) and -PresentMonCsv to merge existing captures.' }
    if ($TracePresent -or $RecoverTrace) { throw 'Merge mode takes only saved captures.' }
    python $merge $FrameCapture $PresentMonCsv @pacingArgs
    exit $LASTEXITCODE
}

function Find-Wpr {
    if ($WprExe) {
        $file = Get-Item -LiteralPath $WprExe
        if ($file.PSIsContainer) { throw '-WprExe must point to wpr.exe.' }
        return $file
    }
    $candidates = @(Get-Command wpr.exe -All -CommandType Application -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty Source)
    foreach ($key in @('HKLM:\SOFTWARE\Microsoft\Windows Kits\Installed Roots',
                      'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows Kits\Installed Roots')) {
        $kits = (Get-ItemProperty -LiteralPath $key -Name KitsRoot10 -ErrorAction SilentlyContinue).KitsRoot10
        if ($kits) { $candidates += Join-Path $kits 'Windows Performance Toolkit/wpr.exe' }
    }
    $recorder = $candidates | Sort-Object -Unique | ForEach-Object {
        Get-Item -LiteralPath $_ -ErrorAction SilentlyContinue
    } | Sort-Object -Property @{Expression={
        $v = $_.VersionInfo
        [version]::new($v.FileMajorPart, $v.FileMinorPart, $v.FileBuildPart, $v.FilePrivatePart)
    }; Descending=$true} | Select-Object -First 1
    if (!$recorder) { throw 'Windows Performance Recorder was not found. Pass -WprExe with the installed Windows Performance Toolkit path.' }
    return $recorder
}

function Merge-PresentTraceFiles([string]$CaptureStamp, [string]$Target) {
    $prefix = "WPR_initiated_CMI-XeSSFG-${CaptureStamp}_"
    $files = @(Get-ChildItem -LiteralPath $folder -File -Filter "$prefix*.etl")
    if (!$files.Count) { return $false }
    $xperf = Join-Path (Split-Path -Parent $wpr) 'xperf.exe'
    if (!(Test-Path -LiteralPath $xperf -PathType Leaf)) {
        $xperf = (Get-Command xperf.exe -CommandType Application -ErrorAction SilentlyContinue).Source
    }
    if (!$xperf) {
        Write-Warning "The raw capture files are in $folder. Xperf is required to recover them."
        return $false
    }
    $loggers = @(& $xperf -loggers)
    if ($LASTEXITCODE -ne 0) {
        Write-Warning 'Xperf could not list active trace sessions. Run recovery from an elevated PowerShell.'
        return $false
    }
    $loggerPattern = '^\s*Logger Name\s*:\s*(' + [regex]::Escape($prefix) + '.+?)\s*$'
    $allStopped = $true
    foreach ($line in $loggers) {
        if ($line -match $loggerPattern) {
            & $xperf -stop $Matches[1] | Out-Host
            if ($LASTEXITCODE -ne 0) { $allStopped = $false }
        }
    }
    if (!$allStopped) {
        Write-Warning "Xperf could not stop every collector for $CaptureStamp. Retry -RecoverTrace from an elevated PowerShell."
        return $false
    }
    $inputs = @(Get-ChildItem -LiteralPath $folder -File -Filter "$prefix*.etl" | Where-Object Length -gt 0)
    if (!$inputs.Count -or (Test-Path -LiteralPath $Target)) {
        Write-Warning "No nonempty raw trace was available or the merge output already exists: $Target"
        return $false
    }
    if ($inputs.Count -lt $files.Count) {
        Write-Warning "Only $($inputs.Count) of $($files.Count) raw traces contain data. The merged trace will be partial."
    }
    $paths = @($inputs.FullName)
    & $xperf -merge @paths $Target -mergeonly -compress | Out-Host
    if ($LASTEXITCODE -ne 0 -or !(Test-Path -LiteralPath $Target -PathType Leaf) -or (Get-Item -LiteralPath $Target).Length -eq 0) {
        Write-Warning "Xperf could not merge the trace. The original ETL files are preserved in $folder."
        return $false
    }
    Write-Host "Recovered ETL: $Target"
    return $true
}

function Save-PresentTrace([string]$CaptureStamp) {
    $target = Join-Path $folder "xessfg-present-$CaptureStamp.etl"
    if (Test-Path -LiteralPath $target) {
        $target = Join-Path $folder "xessfg-present-$CaptureStamp-recovered.etl"
        if (Test-Path -LiteralPath $target) {
            Write-Warning "Recovery output already exists: $target"
            return $false
        }
    }
    $rawPrefix = "WPR_initiated_CMI-XeSSFG-${CaptureStamp}_"
    if ($RecoverTrace -and (Get-ChildItem -LiteralPath $folder -File -Filter "$rawPrefix*.etl" | Select-Object -First 1)) {
        return (Merge-PresentTraceFiles $CaptureStamp $target)
    }
    & $wpr -stop $target 'CMI XeSS-FG Present' -skipPdbGen -instancename "CMI-XeSSFG-$CaptureStamp" | Out-Host
    $stopStatus = $LASTEXITCODE
    if ($stopStatus -eq 0 -and (Test-Path -LiteralPath $target -PathType Leaf) -and (Get-Item -LiteralPath $target).Length -gt 0) {
        Write-Host "WPR: $target"
        return $true
    }
    if (Test-Path -LiteralPath $target) {
        $target = Join-Path $folder "xessfg-present-$CaptureStamp-raw.etl"
    }
    if (Merge-PresentTraceFiles $CaptureStamp $target) { return $true }
    if ($stopStatus -eq 0xc5583000) {
        Write-Warning "WPR found no running trace profile for $CaptureStamp."
    } else {
        Write-Warning "WPR could not save $CaptureStamp (exit $stopStatus). From an elevated PowerShell, retry with -RecoverTrace $CaptureStamp."
    }
    return $false
}

$folder = Join-Path $GameDirectory 'logs/cmi-particle-frames'
if ($RecoverTrace -and $TracePresent) { throw 'Run -RecoverTrace and -TracePresent separately.' }
if ($TracePresent -or $RecoverTrace) {
    $recorder = Find-Wpr
    $wpr = $recorder.FullName
    Write-Host "WPR: $wpr ($($recorder.VersionInfo.FileVersion))"
    if ($recorder.VersionInfo.FileBuildPart -lt 19650) {
        Write-Warning 'This WPR build can fail to save traces with 0x80010106. Prefer Windows Performance Toolkit WPR via -WprExe.'
    }
}
if ($RecoverTrace) {
    $saved = $true
    foreach ($stamp in $RecoverTrace) {
        if (!(Save-PresentTrace $stamp)) { $saved = $false }
    }
    if (!$saved) { exit 1 }
    exit 0
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

New-Item -ItemType Directory -Path $folder -Force | Out-Null
$started = [DateTime]::UtcNow
$stamp = $started.ToString('yyyyMMdd-HHmmss-fff')
$csv = Join-Path $folder "presentmon-$stamp-$GamePid.csv"
$traceActive = $false
$traceSaved = $true
if ($TracePresent) {
    $profile = Join-Path $PSScriptRoot 'xessfg-present.wprp'
    $instance = "CMI-XeSSFG-$stamp-$GamePid"
    & $wpr -start CPU -start GPU -start "$profile!CmiPresent" -filemode -recordtempto $folder -instancename $instance
    if ($LASTEXITCODE -ne 0) { throw 'WPR could not start the CPU/GPU trace. Run -TracePresent from an elevated PowerShell.' }
    $traceActive = $true
}
try {
    Write-Host "Recording Minecraft PID $GamePid for $Seconds seconds."
    Write-Host 'In Minecraft, run /cmi particle profile frames 10 now, then close chat.'
    & $PresentMonExe --process_id $GamePid --session_name "CMI-XeSSFG-$stamp-$GamePid" --qpc_time `
        --track_frame_type --track_app_timing --write_frame_id --no_track_input --no_console_stats `
        --timed $Seconds --terminate_after_timed --output_file $csv
    $presentMonStatus = $LASTEXITCODE
} finally {
    if ($traceActive) {
        $traceSaved = Save-PresentTrace "$stamp-$GamePid"
    }
}
if ($presentMonStatus -ne 0) {
    throw "PresentMon exited with code $presentMonStatus. If Windows denied ETW access, run this script in an elevated PowerShell."
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
    if (!$traceSaved) { exit 1 }
    exit 0
}
if (!(Get-Command python -ErrorAction SilentlyContinue)) { throw 'Python 3 is required to merge the saved CSV files.' }
python $merge $capture.FullName $csv @pacingArgs
if ($LASTEXITCODE -eq 0 -and !$traceSaved) { exit 1 }
exit $LASTEXITCODE
