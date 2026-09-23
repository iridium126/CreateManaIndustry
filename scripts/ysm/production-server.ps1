param(
    [Parameter(Mandatory=$true)][string]$InstalledServer,
    [Parameter(Mandatory=$true)][string]$NeoForgeVersion,
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [string]$ModSource,
    [string]$ModelFile,
    [string]$PlaintextDirectory,
    [ValidateRange(0,65535)][int]$RenderPort = 0,
    [switch]$FullCMI,
    [switch]$FullProbe,
    [switch]$MultiplayerProbe,
    [switch]$LateObserverProbe,
    [switch]$WithoutHex,
    [switch]$WithoutYsm,
    [switch]$LiveApply,
    [switch]$ManagerApply
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $PSScriptRoot 'common.ps1')
if ($FullProbe -and (-not $FullCMI -or $WithoutHex -or $WithoutYsm)) { throw 'Full probe requires CMI, Hexcasting and YSM.' }
if ($MultiplayerProbe -and -not $FullProbe) { throw 'Multiplayer probe requires the full probe.' }
if ($LateObserverProbe -and -not $MultiplayerProbe) { throw 'Late observer probe requires the multiplayer probe.' }
$installed = (Resolve-Path -LiteralPath $InstalledServer).Path.Replace('\', '/')
$sourceMods = if ($ModSource) { Join-Path (Resolve-Path -LiteralPath $ModSource).Path 'mods' } else { Join-Path $installed 'mods' }
$sourceArgs = Join-Path $installed "libraries/net/neoforged/neoforge/$NeoForgeVersion/win_args.txt"
$acceptedEula = Join-Path $installed 'eula.txt'
if (-not (Test-Path $acceptedEula) -or -not ((Get-Content $acceptedEula) -match '^eula=true$')) {
    throw 'Use an existing server whose EULA has already been accepted.'
}
$probe = Join-Path $repo 'build/ysm-production-probe/libs/cmi-ysm-production-probe.jar'
if (-not $FullCMI -and -not (Test-Path $probe)) { throw 'Build scripts/ysm/production-probe with Gradle jar first.' }
$run = Join-Path $repo ('build/ysm-production-server-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path "$run/mods" -Force | Out-Null
if ($FullCMI) {
    $cmiJar = Get-CmiArtifact $repo
    Copy-Item -LiteralPath $cmiJar -Destination "$run/mods"
    if ($FullProbe) { Copy-Item -LiteralPath (Join-Path $repo 'build/ysm-full-integration-probe/libs/cmi-ysm-full-integration-probe.jar') -Destination "$run/mods" }
    $required = @('create-1.21.1-6.0.10.jar')
    if (-not $WithoutHex) {
        $gradleCache = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
        $hexCache = Join-Path $gradleCache 'caches/modules-2/files-2.1/hexcasting/hexcasting-neoforge-1.21.1-0.12.0-devel-pre-53'
        $hexJar = Get-ChildItem -LiteralPath $hexCache -Recurse -Filter '*.jar' -File -ErrorAction SilentlyContinue | Select-Object -First 1
        if (-not $hexJar) { throw 'The compiled Hexcasting pre-53 dependency is unavailable.' }
        Copy-Item -LiteralPath $hexJar.FullName -Destination "$run/mods/hexcasting-neoforge-1.21.1-0.12.0-devel-pre-53.jar"
        $required += @(
        'paucal-0.7.1+1.21.1-neoforge.jar', 'Patchouli-1.21.1-93-NEOFORGE.jar',
        'inline-neoforge-1.21.1-1.2.2.jar', 'cloth-config-15.0.140-neoforge.jar',
        'kotlinforforge-5.11.0-all.jar', 'architectury-13.0.8-neoforge.jar', 'caelus-neoforge-7.0.1+1.21.1.jar')
    }
    foreach ($name in $required) {
        $match = @(Get-ChildItem -LiteralPath $sourceMods -File | Where-Object { $_.Name.EndsWith($name, [StringComparison]::OrdinalIgnoreCase) })
        if ($match.Count -ne 1) { throw "Expected exactly one installed mod ending in $name" }
        Copy-Item -LiteralPath $match[0].FullName -Destination "$run/mods"
    }
} else { Copy-Item -LiteralPath $probe -Destination "$run/mods" }
if (-not $WithoutYsm) { Copy-Item -LiteralPath (Join-Path $repo 'run/mods/ysm-2.6.5-neoforge+mc1.21.1-release.jar') -Destination "$run/mods" }
Copy-Item -LiteralPath $acceptedEula -Destination "$run/eula.txt"
$sampleArgs = @()
if ($ModelFile) {
    New-Item -ItemType Directory -Path "$run/config/yes_steve_model/custom" -Force | Out-Null
    Copy-Item -LiteralPath $ModelFile -Destination "$run/config/yes_steve_model/custom/cmi_sample.ysm"
    $sampleArgs = @('-Dcmi.ysm.sample=cmi_sample.ysm')
}
if ($PlaintextDirectory) {
    if (-not (Test-Path -LiteralPath (Join-Path $PlaintextDirectory 'ysm.json'))) { throw 'Plaintext model must contain ysm.json.' }
    New-Item -ItemType Directory -Path "$run/config/yes_steve_model/custom" -Force | Out-Null
    Copy-Item -LiteralPath $PlaintextDirectory -Destination "$run/config/yes_steve_model/custom/cmi_plain_sample" -Recurse
    $sampleArgs += '-Dcmi.ysm.plaintext=cmi_plain_sample'
}
$arguments = (Get-Content -Raw $sourceArgs).Replace('libraries/', "$installed/libraries/")
$arguments = $arguments.Replace('-DlibraryDirectory=libraries', "-DlibraryDirectory=$installed/libraries")
if ($installed.Contains(' ')) { throw 'This validation launcher requires an installed server path without spaces.' }
Set-Content "$run/launch.args" $arguments -Encoding utf8
if ($RenderPort -ne 0) {
    if (-not $ModelFile -or -not $PlaintextDirectory) { throw 'Render probe requires both source and exported models.' }
    $sampleArgs += '-Dcmi.ysm.waitClient=true'
    if ($LiveApply -or $ManagerApply) { $sampleArgs += '-Dcmi.ysm.liveApply=true' }
    if ($ManagerApply) { $sampleArgs += '-Dcmi.ysm.managerApply=true' }
}
if ($ManagerApply -and $RenderPort -eq 0) { $sampleArgs += '-Dcmi.ysm.managerApply=true' }
if ($MultiplayerProbe) { $sampleArgs += '-Dcmi.ysm.multiplayer=true' }
if ($LateObserverProbe) { $sampleArgs += '-Dcmi.ysm.lateObserver=true' }
$maxPlayers = if ($MultiplayerProbe) { 2 } else { 1 }
Set-Content "$run/server.properties" "server-ip=127.0.0.1`nserver-port=$RenderPort`nonline-mode=false`nlevel-name=ysm-probe`nview-distance=2`nsimulation-distance=2`nmax-players=$maxPlayers" -Encoding utf8
Write-Output "Isolated YSM production server: $run (NeoForge $NeoForgeVersion)"
Push-Location $run
try {
    & (Join-Path $JavaHome 'bin/java.exe') -Xmx2G @sampleArgs '-Dcmi.ysm.fixture=mods/ysm-2.6.5-neoforge+mc1.21.1-release.jar' '@launch.args' --nogui *> "$run/console.log"
    if ($FullCMI) {
        if ($LASTEXITCODE -ne 0) { throw "Full CMI server exited with $LASTEXITCODE; inspect $run/console.log" }
        if ($FullProbe -and -not (Select-String -LiteralPath "$run/console.log" -SimpleMatch 'CMI_YSM_FULL PASS:')) {
            throw "Full integration probe did not pass; inspect $run/console.log"
        }
        return
    }
    if ($LASTEXITCODE -ne 0 -or -not (Select-String -LiteralPath "$run/console.log" -SimpleMatch 'CMI_YSM_PROBE PASS:')) {
        throw "Native probe did not pass; inspect $run/console.log"
    }
    Write-Output 'PASS: production native probe'
} finally { Pop-Location }
