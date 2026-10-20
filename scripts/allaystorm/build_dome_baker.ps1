param([string]$Name = 'dome_bake')
$ErrorActionPreference = 'Stop'
if ($Name -notmatch '^[a-zA-Z0-9_-]+$') { throw 'Invalid offline library name' }
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$taskOutput = Join-Path $taskRoot 'build/dome-v13'
New-Item -ItemType Directory -Path $taskOutput -Force | Out-Null
$taskCompiler = (Get-Command cl.exe -ErrorAction Stop).Source
$taskVcvars = (Resolve-Path (Join-Path (Split-Path $taskCompiler) '../../../../../../Auxiliary/Build/vcvars64.bat')).Path
$taskSource = Join-Path $PSScriptRoot 'dome_bake_native.cpp'
$taskDll = Join-Path $taskOutput "$Name.dll"
$taskObject = Join-Path $taskOutput "$Name.obj"
$taskLibrary = Join-Path $taskOutput "$Name.lib"
$taskBatch = Join-Path $taskOutput 'compile-baker.cmd'
@"
@call "$taskVcvars" >nul
@if errorlevel 1 exit /b 1
@cl /nologo /O2 /fp:precise /std:c++17 /EHsc /MT /LD "$taskSource" /Fo"$taskObject" /link /OUT:"$taskDll" /IMPLIB:"$taskLibrary"
"@ | Set-Content -LiteralPath $taskBatch -Encoding ASCII
& cmd.exe /d /c $taskBatch
if ($LASTEXITCODE -ne 0) { throw 'Offline Dome baker compilation failed' }
