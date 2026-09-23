param(
    [Parameter(Mandatory=$true)][string]$Instance,
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [string]$ModSource,
    [ValidateRange(0,65535)][int]$RenderPort = 0,
    [switch]$FullCMI,
    [switch]$FullProbe,
    [switch]$Diagnostic,
    [switch]$LiveApply,
    [switch]$ManagerApply,
    [ValidatePattern('^[A-Za-z0-9_]{3,16}$')][string]$PlayerName = 'YsmProbe',
    [switch]$ObserverProbe
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $PSScriptRoot 'common.ps1')
$instancePath = (Resolve-Path -LiteralPath $Instance).Path
$versionName = Split-Path $instancePath -Leaf
$manifest = Get-Content -Raw -LiteralPath (Join-Path $instancePath "$versionName.json") | ConvertFrom-Json
if ($manifest.inheritsFrom) { throw 'An installed, merged NeoForge version manifest is required.' }
$minecraft = Split-Path (Split-Path $instancePath -Parent) -Parent
$libraries = Join-Path $minecraft 'libraries'
$run = Join-Path $repo ('build/ysm-production-client-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path "$run/mods", "$run/natives" -Force | Out-Null
# A fresh test directory otherwise pauses at the accessibility onboarding screen,
# preventing --quickPlayMultiplayer from ever starting its connection.
Set-Content -LiteralPath "$run/options.txt" -Value "narrator:0`nonboardAccessibility:false" -Encoding utf8
Copy-Item -LiteralPath (Join-Path $repo 'run/mods/ysm-2.6.5-neoforge+mc1.21.1-release.jar') -Destination "$run/mods"
if ($FullCMI) {
    if (-not $ModSource) { throw 'Full CMI client validation requires a mod source directory.' }
    Copy-Item -LiteralPath (Get-CmiArtifact $repo) -Destination "$run/mods"
    if ($FullProbe) { Copy-Item -LiteralPath (Join-Path $repo 'build/ysm-full-integration-probe/libs/cmi-ysm-full-integration-probe.jar') -Destination "$run/mods" }
    $gradleCache = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
    $hexCache = Join-Path $gradleCache 'caches/modules-2/files-2.1/hexcasting/hexcasting-neoforge-1.21.1-0.12.0-devel-pre-53'
    $hexJar = Get-ChildItem -LiteralPath $hexCache -Recurse -Filter '*.jar' -File -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $hexJar) { throw 'The compiled Hexcasting pre-53 dependency is unavailable.' }
    Copy-Item -LiteralPath $hexJar.FullName -Destination "$run/mods/hexcasting-neoforge-1.21.1-0.12.0-devel-pre-53.jar"
    $sourceMods = Join-Path (Resolve-Path -LiteralPath $ModSource).Path 'mods'
    $required = @('create-1.21.1-6.0.10.jar', 'paucal-0.7.1+1.21.1-neoforge.jar',
        'Patchouli-1.21.1-93-NEOFORGE.jar', 'inline-neoforge-1.21.1-1.2.2.jar',
        'cloth-config-15.0.140-neoforge.jar', 'kotlinforforge-5.11.0-all.jar',
        'architectury-13.0.8-neoforge.jar', 'caelus-neoforge-7.0.1+1.21.1.jar')
    foreach ($name in $required) {
        $match = @(Get-ChildItem -LiteralPath $sourceMods -File | Where-Object { $_.Name.EndsWith($name, [StringComparison]::OrdinalIgnoreCase) })
        if ($match.Count -ne 1) { throw "Expected exactly one installed mod ending in $name" }
        Copy-Item -LiteralPath $match[0].FullName -Destination "$run/mods"
    }
    Copy-Item -LiteralPath (Join-Path $repo 'run/mods/sodium-neoforge-0.8.13+mc1.21.1.jar') -Destination "$run/mods"
} elseif ($RenderPort -ne 0) {
    Copy-Item -LiteralPath (Join-Path $repo 'build/ysm-production-probe/libs/cmi-ysm-production-probe.jar') -Destination "$run/mods"
}
Copy-Item -Path "$instancePath/$versionName-natives/*" -Destination "$run/natives" -Recurse
if ($Diagnostic) {
    Copy-Item -LiteralPath (Join-Path $repo 'build/ysm-client-diagnostic/libs/cmi-ysm-client-diagnostic.jar') -Destination "$run/mods"
}

function Test-Rules($rules) {
    if (-not $rules) { return $true }
    $allowed = $false
    foreach ($rule in $rules) {
        if ($rule.features) { continue }
        if ($rule.os.name -and $rule.os.name -ne 'windows') { continue }
        if ($rule.os.arch -and $rule.os.arch -notin @('amd64', 'x86_64')) { continue }
        if ($rule.os.version -and [Environment]::OSVersion.Version.ToString() -notmatch $rule.os.version) { continue }
        $allowed = $rule.action -eq 'allow'
    }
    return $allowed
}
$selectedLibraries = [ordered]@{}
foreach ($library in $manifest.libraries) {
    if (-not (Test-Rules $library.rules)) { continue }
    $coordinate = $library.name.Split(':')
    $key = $coordinate[0] + ':' + $coordinate[1]
    if ($coordinate.Length -gt 3) { $key += ':' + $coordinate[3] }
    $selectedLibraries[$key] = $library
}
$classpath = foreach ($library in $selectedLibraries.Values) {
    if (-not (Test-Rules $library.rules)) { continue }
    $relative = $library.downloads.artifact.path
    if (-not $relative) {
        $parts = $library.name.Split(':')
        $relative = $parts[0].Replace('.', '/') + '/' + $parts[1] + '/' + $parts[2] + '/' + $parts[1] + '-' + $parts[2] + '.jar'
    }
    $path = Join-Path $libraries $relative
    if (-not (Test-Path -LiteralPath $path)) { throw "Missing installed library: $relative" }
    $path
}
$classpath += Join-Path $instancePath "$versionName.jar"
$values = @{
    natives_directory = "$run/natives"; launcher_name = 'CMI-YSM-validation'; launcher_version = '1'
    classpath = ($classpath -join ';'); library_directory = $libraries; classpath_separator = ';'
    version_name = $versionName; game_directory = $run; assets_root = "$minecraft/assets"
    assets_index_name = $manifest.assetIndex.id; auth_player_name = $PlayerName
    auth_uuid = $(if ($ObserverProbe) { '12a327cd08ba4d39af000c3555532834' } else { 'd0fa744657e34f61972664636b9a0110' }); auth_access_token = '0'
    clientid = '0'; auth_xuid = '0'; user_type = 'legacy'; version_type = 'CMI validation'
}
function Expand-Arguments($arguments) {
    foreach ($argument in $arguments) {
        if ($argument -is [string]) { $items = @($argument) }
        elseif (Test-Rules $argument.rules) { $items = @($argument.value) }
        else { continue }
        foreach ($item in $items) {
            foreach ($key in $values.Keys) { $item = $item.Replace('${' + $key + '}', [string]$values[$key]) }
            if ($item.Contains('${')) { throw "Unresolved launch argument: $item" }
            $item
        }
    }
}
$probeArguments = @()
$gameArguments = @()
if ($RenderPort -ne 0) {
    $probeArguments = @('-Dcmi.ysm.renderProbe=true', '-Dcmi.ysm.fixture=mods/ysm-2.6.5-neoforge+mc1.21.1-release.jar')
    if ($LiveApply -or $ManagerApply) { $probeArguments += '-Dcmi.ysm.liveApply=true' }
    if ($ManagerApply) { $probeArguments += '-Dcmi.ysm.managerApply=true' }
    $gameArguments = @('--quickPlayMultiplayer', "127.0.0.1:$RenderPort")
}
if ($Diagnostic -and $RenderPort -ne 0) { $probeArguments += "-Dcmi.ysm.autoConnect=127.0.0.1:$RenderPort" }
if ($FullProbe) { $probeArguments += '-Dcmi.ysm.managerApply=true' }
if ($ObserverProbe) { $probeArguments += '-Dcmi.ysm.observerProbe=true' }
$arguments = @('-Xmx2G') + $probeArguments + @(Expand-Arguments $manifest.arguments.jvm) + @($manifest.mainClass) + @(Expand-Arguments $manifest.arguments.game) + $gameArguments
$argumentFile = Join-Path $run 'launch.args'
$arguments | ForEach-Object { '"' + $_.Replace('\', '\\').Replace('"', '\"') + '"' } | Set-Content -Encoding utf8 $argumentFile
Write-Output "Isolated YSM production client: $run"
Push-Location $run
try {
    & (Join-Path $JavaHome 'bin/java.exe') "@$argumentFile" *> "$run/console.log"
    if ($LASTEXITCODE -ne 0) { throw "Client exited with $LASTEXITCODE; see $run/console.log" }
} finally { Pop-Location }
