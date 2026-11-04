param([int]$Jobs=8)
$ErrorActionPreference='Stop'
$TaskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$DependencyFile=Join-Path $TaskRoot '.gradle/particle-rt/dependencies.json'
if(!(Test-Path -LiteralPath $DependencyFile)){& python (Join-Path $PSScriptRoot 'prepare_rt.py');if($LASTEXITCODE -ne 0){throw 'RT dependencies unavailable'}}
$Dependencies=Get-Content -LiteralPath $DependencyFile -Raw | ConvertFrom-Json
$VsWhere=Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
$VsRoot=(& $VsWhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath).Trim()
if(!$VsRoot){throw 'MSVC x64 unavailable'}
Import-Module (Join-Path $VsRoot 'Common7/Tools/Microsoft.VisualStudio.DevShell.dll')
Enter-VsDevShell -VsInstallPath $VsRoot -SkipAutomaticLocation -DevCmdArguments '-arch=x64 -host_arch=x64'
$Cmake=Join-Path $VsRoot 'Common7/IDE/CommonExtensions/Microsoft/CMake/CMake/bin/cmake.exe'
$Ninja=Join-Path $VsRoot 'Common7/IDE/CommonExtensions/Microsoft/CMake/Ninja/ninja.exe'
$Build=Join-Path $TaskRoot '.gradle/particle-rt/build'
& $Cmake -S (Join-Path $TaskRoot 'native/particle_rt') -B $Build -G Ninja "-DCMAKE_MAKE_PROGRAM=$Ninja" '-DCMAKE_BUILD_TYPE=Release' "-DCUDA_ROOT=$($Dependencies.cuda_cudart.root)" "-DCRT_ROOT=$($Dependencies.cuda_nvcc.root)" "-DOPTIX_ROOT=$($Dependencies.optix.root)" "-DCCCL_ROOT=$($Dependencies.cuda_cccl.root)"
if($LASTEXITCODE -ne 0){throw 'Particle RT configure failed'}
& $Cmake --build $Build --parallel $Jobs
if($LASTEXITCODE -ne 0){throw 'Particle RT build failed'}
Write-Output (Join-Path $Build 'CmiParticleRt.dll')
