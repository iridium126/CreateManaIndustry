param([string]$YsmJar = 'run/mods/ysm-2.6.5-neoforge+mc1.21.1-release.jar')
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$classpathFile = Join-Path $repo 'build/moddev/serverLegacyClasspath.txt'
if (-not (Test-Path -LiteralPath $classpathFile)) {
    throw 'Run Gradle prepareServerRun first to resolve the local ASM classpath.'
}
$classpath = (Get-Content -Raw -LiteralPath $classpathFile).Trim()
$vmArgs = Get-Content -LiteralPath (Join-Path $repo 'build/moddev/serverRunVmArgs.txt')
$moduleLine = $vmArgs[[Array]::IndexOf($vmArgs, '-p') + 1].Trim('"').Replace('\\', '\')
$classpath = "$classpath;$moduleLine"
$output = Join-Path $repo 'build/ysm-runtime-validation'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$source = Join-Path $repo 'src/main/java/com/iridium126/createmanaindustry/compat/ysm/YsmRuntimeSymbols.java'
$test = Join-Path $PSScriptRoot 'YsmRuntimeSymbolsValidation.java'
& javac -encoding UTF-8 -cp $classpath -d $output $source $test
if ($LASTEXITCODE -ne 0) { throw 'Probe compilation failed' }
& java -cp "$output;$classpath" com.iridium126.createmanaindustry.compat.ysm.YsmRuntimeSymbolsValidation (Join-Path $repo $YsmJar)
if ($LASTEXITCODE -ne 0) { throw 'Probe verification failed' }
$geometry = Join-Path $repo 'src/main/java/com/iridium126/createmanaindustry/compat/ysm/model/YsmGeometry.java'
& javac -encoding UTF-8 -d $output $geometry (Join-Path (Split-Path $geometry) 'YsmGeometryIO.java') (Join-Path (Split-Path $geometry) 'YsmBakedGeometry.java') (Join-Path $PSScriptRoot 'YsmGeometryValidation.java')
if ($LASTEXITCODE -ne 0) { throw 'Geometry validation compilation failed' }
& java -cp $output YsmGeometryValidation
if ($LASTEXITCODE -ne 0) { throw 'Geometry validation failed' }
