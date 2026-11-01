param([Parameter(Mandatory=$true)][string]$GameDirectory,[switch]$InstallMod)
$ErrorActionPreference='Stop'
function Get-R7Sha256([string]$Path){
    $Stream=[IO.File]::OpenRead($Path);$Algorithm=[Security.Cryptography.SHA256]::Create()
    try{return [BitConverter]::ToString($Algorithm.ComputeHash($Stream)).Replace('-','').ToLowerInvariant()}
    finally{$Algorithm.Dispose();$Stream.Dispose()}
}
$Bundle=[IO.Path]::GetFullPath($PSScriptRoot)
$Game=[IO.Path]::GetFullPath($GameDirectory)
if(!(Test-Path -LiteralPath $Game -PathType Container)){throw "Game directory does not exist: $Game"}
$Manifest=Get-Content -LiteralPath (Join-Path $Bundle 'SHA256SUMS.json') -Raw | ConvertFrom-Json
foreach($file in $Manifest.files.PSObject.Properties){
    $path=[IO.Path]::GetFullPath((Join-Path $Bundle $file.Name))
    if(!$path.StartsWith($Bundle+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Bundle path escaped its directory'}
    if((Get-R7Sha256 $path) -ne $file.Value){throw "Bundle checksum mismatch: $($file.Name)"}
}
$Hardware=Join-Path $Game 'cmi-hardware'
$Version=Join-Path $Hardware ("r7-20261101-"+$Manifest.runtime_id)
if(!(Test-Path -LiteralPath $Version)){
    New-Item -ItemType Directory -Path $Version | Out-Null
    foreach($name in @('native','source','sdk','notices')){Copy-Item -LiteralPath (Join-Path $Bundle "hardware/$name") -Destination (Join-Path $Version $name) -Recurse}
}
# Existing immutable installations must match too; do not overwrite them.
foreach($file in $Manifest.files.PSObject.Properties){if($file.Name.StartsWith('hardware/')){
    $path=Join-Path $Version $file.Name.Substring(9)
    if(!(Test-Path -LiteralPath $path)){New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($path)) -Force | Out-Null;Copy-Item -LiteralPath (Join-Path $Bundle $file.Name) -Destination $path};if((Get-R7Sha256 $path) -ne $file.Value){throw "Existing R7 installation differs: $path"}
}}
$Dependencies=Get-Content -LiteralPath (Join-Path $Version 'sdk/dependencies-template.json') -Raw | ConvertFrom-Json
foreach($entry in $Dependencies.PSObject.Properties){$entry.Value.root=[IO.Path]::GetFullPath((Join-Path $Version ("sdk/"+$entry.Name)))}
$DependencyFile=Join-Path $Version 'sdk/dependencies.json'
$Dependencies | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $DependencyFile -Encoding utf8
$Config=Join-Path $Hardware 'config.json'
if(Test-Path -LiteralPath $Config){Copy-Item -LiteralPath $Config -Destination ($Config+'.backup-'+[DateTime]::UtcNow.ToString('yyyyMMddHHmmssfff'))}
@{native=(Join-Path $Version 'native');source=(Join-Path $Version 'source');dependencies=$DependencyFile} | ConvertTo-Json | Set-Content -LiteralPath $Config -Encoding utf8
if($InstallMod){
    $Jar=Join-Path $Bundle $Manifest.jar
    $Mods=Join-Path $Game 'mods';New-Item -ItemType Directory -Path $Mods -Force | Out-Null
    $Target=Join-Path $Mods $Manifest.jar
    $Existing=@(Get-ChildItem -LiteralPath $Mods -File -Filter 'createmanaindustry*.jar')
    foreach($file in $Existing){if($file.FullName -ne $Target){throw "Move the previous CMI jar out of mods before installing: $($file.FullName)"}}
    if(Test-Path -LiteralPath $Target){if((Get-R7Sha256 $Target) -ne $Manifest.files.($Manifest.jar)){throw 'Existing diagnostic jar has a different checksum'}}else{Copy-Item -LiteralPath $Jar -Destination $Target}
}
Write-Output "Installed optional R7 runtime: $Version"
Write-Output 'Default backend is production. After restarting the client, use /cmi particle profile hardware status and the commands in R7-client-guide.md.'
