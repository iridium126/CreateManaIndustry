$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location $repo
try {
    New-Item -ItemType Directory -Force build/causeway-validation/classes | Out-Null
    dotnet build scripts/markov/upstream/Upstream.csproj -c Release -o build/markov-upstream
    if ($LASTEXITCODE -ne 0) { throw 'Upstream build failed' }
    dotnet run --project scripts/markov/CausewayReference.csproj -c Release -- build/markov-upstream/Upstream.dll src/main/resources/data/createmanaindustry/markov/karst_causeways.xml build/causeway-validation
    if ($LASTEXITCODE -ne 0) { throw 'Upstream causeway execution failed' }
    javac -d build/causeway-validation/classes src/main/java/com/iridium126/createmanaindustry/worldgen/markov/DotNetRandom.java src/main/java/com/iridium126/createmanaindustry/worldgen/markov/MarkovModel.java src/main/java/com/iridium126/createmanaindustry/dimension/gen/AllvrSanctuary.java scripts/markov/CausewayValidation.java
    if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed' }
    java -cp 'build/causeway-validation/classes;src/main/resources' CausewayValidation
    if ($LASTEXITCODE -ne 0) { throw 'Causeway parity/geometry validation failed' }
} finally { Pop-Location }
