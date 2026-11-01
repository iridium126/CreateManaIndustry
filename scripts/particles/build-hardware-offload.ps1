param([int]$Jobs=8)
$ErrorActionPreference='Stop'
$TaskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$Cache=Join-Path $TaskRoot '.gradle/neighbor-rt'
$Dependencies=Get-Content -LiteralPath (Join-Path $Cache 'dependencies.json') -Raw | ConvertFrom-Json
if(Test-Path -LiteralPath (Join-Path $TaskRoot '.gradle/hardware-offload/media-headers/lock.json')){
    & python (Join-Path $PSScriptRoot 'prepare_r7_media_headers.py')
    if($LASTEXITCODE -ne 0){throw 'Pinned optional media headers failed verification'}
}
$VsWhere=Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
$VsRoot=(& $VsWhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath).Trim()
if(!$VsRoot){throw 'MSVC x64 unavailable'}
Import-Module (Join-Path $VsRoot 'Common7/Tools/Microsoft.VisualStudio.DevShell.dll')
Enter-VsDevShell -VsInstallPath $VsRoot -SkipAutomaticLocation -DevCmdArguments '-arch=x64 -host_arch=x64'
$Cmake=Join-Path $VsRoot 'Common7/IDE/CommonExtensions/Microsoft/CMake/CMake/bin/cmake.exe'
$Ninja=Join-Path $VsRoot 'Common7/IDE/CommonExtensions/Microsoft/CMake/Ninja/ninja.exe'
$Build=Join-Path $TaskRoot '.gradle/hardware-offload/build'
& $Cmake -S (Join-Path $TaskRoot 'native/offload') -B $Build -G Ninja "-DCMAKE_MAKE_PROGRAM=$Ninja" '-DCMAKE_BUILD_TYPE=Release' "-DCUDA_ROOT=$($Dependencies.cuda_cudart.root)" "-DNVRTC_ROOT=$($Dependencies.cuda_nvrtc.root)" "-DCRT_ROOT=$($Dependencies.cuda_nvcc.root)" "-DOPTIX_ROOT=$($Dependencies.optix.root)"
if($LASTEXITCODE -ne 0){throw 'R7 native configure failed'}
& $Cmake --build $Build --parallel $Jobs
if($LASTEXITCODE -ne 0){throw 'R7 native build failed'}
Get-ChildItem (Join-Path $Dependencies.cuda_nvrtc.root 'bin') -Recurse -Filter '*.dll' | Copy-Item -Destination $Build
Write-Output (Join-Path $Build 'CmiHardwareOffload.dll')
