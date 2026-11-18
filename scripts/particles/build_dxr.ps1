param([int]$Jobs = 8, [switch]$Publish)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
$vs = (& $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath).Trim()
if (!$vs) { throw 'MSVC x64 unavailable' }
Import-Module (Join-Path $vs 'Common7/Tools/Microsoft.VisualStudio.DevShell.dll')
Enter-VsDevShell -VsInstallPath $vs -SkipAutomaticLocation -DevCmdArguments '-arch=x64 -host_arch=x64'
$cmake = Join-Path $vs 'Common7/IDE/CommonExtensions/Microsoft/CMake/CMake/bin/cmake.exe'
$ninja = Join-Path $vs 'Common7/IDE/CommonExtensions/Microsoft/CMake/Ninja/ninja.exe'
$build = Join-Path $root '.gradle/particle-dxr/build'
& $cmake -S (Join-Path $root 'src/main/native/particle_dxr') -B $build -G Ninja "-DCMAKE_MAKE_PROGRAM=$ninja" '-DCMAKE_BUILD_TYPE=Release'
if ($LASTEXITCODE -ne 0) { throw 'Storm DXR configure failed' }
& $cmake --build $build --parallel $Jobs
if ($LASTEXITCODE -ne 0) { throw 'Storm DXR build failed' }

$dll = Join-Path $build 'CmiParticleDxr.dll'
if ($Publish) {
    & python (Join-Path $root 'scripts/particles/publish_native.py') dxr
    if ($LASTEXITCODE -ne 0) { throw 'Storm DXR publication failed' }
}
Write-Output $dll
