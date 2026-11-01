param([int]$Jobs=8)
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$cache=Join-Path $taskRoot '.gradle/neighbor-rt'
& python (Join-Path $PSScriptRoot 'prepare-rt-deps.py')
if($LASTEXITCODE -ne 0){throw 'Dependency preparation failed'}
$deps=Get-Content (Join-Path $cache 'dependencies.json') -Raw | ConvertFrom-Json
$vswhere=Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
$vs=(& $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath).Trim()
if(!$vs){throw 'MSVC x64 unavailable'}
Import-Module (Join-Path $vs 'Common7/Tools/Microsoft.VisualStudio.DevShell.dll')
Enter-VsDevShell -VsInstallPath $vs -SkipAutomaticLocation -DevCmdArguments '-arch=x64 -host_arch=x64'
$cmake=Join-Path $vs 'Common7/IDE/CommonExtensions/Microsoft/CMake/CMake/bin/cmake.exe'
$ninja=Join-Path $vs 'Common7/IDE/CommonExtensions/Microsoft/CMake/Ninja/ninja.exe'
$build=Join-Path $cache 'build'
& $cmake -S (Join-Path $taskRoot 'native/neighbor') -B $build -G Ninja "-DCMAKE_MAKE_PROGRAM=$ninja" '-DCMAKE_BUILD_TYPE=Release' "-DCUDA_ROOT=$($deps.cuda_cudart.root)" "-DNVRTC_ROOT=$($deps.cuda_nvrtc.root)" "-DCRT_ROOT=$($deps.cuda_nvcc.root)" "-DOPTIX_ROOT=$($deps.optix.root)"
if($LASTEXITCODE -ne 0){throw 'Neighbor native configure failed'}
& $cmake --build $build --parallel $Jobs
if($LASTEXITCODE -ne 0){throw 'Neighbor native build failed'}
Get-ChildItem (Join-Path $deps.cuda_nvrtc.root 'bin') -Recurse -Filter '*.dll' | Copy-Item -Destination $build
Write-Output (Join-Path $build 'CmiNeighborExperiment.dll')
