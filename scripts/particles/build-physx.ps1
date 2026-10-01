param([string]$CMake, [string]$Ninja, [int]$Jobs = 8)
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$cache = Join-Path $taskRoot '.gradle/physx'
$build = Join-Path $cache 'build-ovphysx-0.6.3'
New-Item -ItemType Directory -Path $cache -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Get-OfficialSdk([string]$Name, [string]$Hash, [string]$Url, [string]$Directory, [long]$Bytes) {
    $archive = Join-Path $cache $Name
    if (!(Test-Path -LiteralPath $archive) -or (Get-Item -LiteralPath $archive).Length -lt $Bytes) {
        # Preserve interrupted transfers for explicit retry instead of treating
        # a partial archive as a usable SDK. No machine-wide install is made.
        & curl.exe --http1.1 -fL -C - --connect-timeout 20 --max-time 600 -o $archive $Url
        if ($LASTEXITCODE -ne 0) { throw "Official SDK download failed: $Name" }
    }
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Hash) { throw "SDK checksum mismatch: $Name" }
    $target = Join-Path $cache $Directory
    $marker = Join-Path $target '.distribution-sha256'
    if (!(Test-Path -LiteralPath $marker) -or (Get-Content -LiteralPath $marker -Raw).Trim() -ne $Hash) {
        # Overwrite files from an interrupted extraction, publish marker last.
        New-Item -ItemType Directory -Path $target -Force | Out-Null
        $zip = [IO.Compression.ZipFile]::OpenRead($archive)
        try {
            foreach ($entry in $zip.Entries) {
                $path = [IO.Path]::GetFullPath((Join-Path $target $entry.FullName))
                if (!$path.StartsWith($target + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid SDK archive path' }
                if ($entry.FullName.EndsWith('/')) { New-Item -ItemType Directory -Path $path -Force | Out-Null }
                else {
                    New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($path)) -Force | Out-Null
                    [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $path, $true)
                }
            }
        } finally { $zip.Dispose() }
        Set-Content -LiteralPath $marker -Value $Hash
    }
    return $target
}
$sdk = Join-Path (Get-OfficialSdk 'ovphysx-windows-x86_64-0.6.3.zip' 'baaaf6981e52b39f29d6498524fae39a2e3a17fa5225bd6db1fc0a5fe88dea92' 'https://github.com/NVIDIA-Omniverse/PhysX/releases/download/ovphysx-0.6.3/ovphysx-windows-x86_64-0.6.3.zip' 'ovphysx-sdk-0.6.3' 131287643) 'ovphysx'
$stage = Join-Path (Get-OfficialSdk 'ovstage-0.2.0.377349-py3-none-win_amd64.whl' 'd1408fd27ad57bc216c8da3ba326fa2a2f9a5abb922c4c42206dd40e0c3e7f3b' 'https://files.pythonhosted.org/packages/09/dd/861c19634b92c692cdad925a731661043afa06147672295ee8e9165ebbcc/ovstage-0.2.0.377349-py3-none-win_amd64.whl' 'ovstage-sdk-0.2.0.377349' 46087437) 'ovstage'
$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
$vs = (& $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath).Trim()
if (!$vs) { throw 'Visual Studio C++ toolchain unavailable' }
Import-Module (Join-Path $vs 'Common7/Tools/Microsoft.VisualStudio.DevShell.dll')
Enter-VsDevShell -VsInstallPath $vs -SkipAutomaticLocation -DevCmdArguments '-arch=x64 -host_arch=x64'
if (!$CMake -or !$Ninja) {
    if (!$CMake) { $CMake = Join-Path $vs 'Common7/IDE/CommonExtensions/Microsoft/CMake/CMake/bin/cmake.exe' }
    if (!$Ninja) { $Ninja = Join-Path $vs 'Common7/IDE/CommonExtensions/Microsoft/CMake/Ninja/ninja.exe' }
}
& $CMake -S (Join-Path $taskRoot 'native/packages') -B $build -G Ninja "-DCMAKE_MAKE_PROGRAM=$Ninja" '-DCMAKE_BUILD_TYPE=Release' "-DCMAKE_PREFIX_PATH=$sdk;$stage"
if ($LASTEXITCODE -ne 0) { throw 'PhysX host SDK configure failed' }
& $CMake --build $build --target CmiPackagePhysxBenchmark --parallel $Jobs
if ($LASTEXITCODE -ne 0) { throw 'PhysX benchmark build failed' }
$env:OVSTAGE_ROOT = $stage
$env:PATH = "$sdk\bin;$sdk\plugins;$stage\bin;$stage\bin\plugins;$stage\bin\plugins\omni.client.lib;$stage\bin\plugins\omni.usd_resolver;$env:PATH"
[pscustomobject]@{Executable = Join-Path $build 'CmiPackagePhysxBenchmark.exe'; SDK = $sdk; StageSDK = $stage; PhysXVersion = '5.11.0'; Distribution = 'ovphysx-0.6.3'}
