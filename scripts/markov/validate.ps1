$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location $repo
try {
    New-Item -ItemType Directory -Force build/markov-validation | Out-Null
    dotnet build scripts/markov/upstream/Upstream.csproj -c Release -o build/markov-upstream
    if ($LASTEXITCODE -ne 0) { throw 'Upstream build failed' }
    dotnet run --project scripts/markov/ReferenceBenchmark.csproj -c Release -- build/markov-upstream/Upstream.dll src/main/resources/data/createmanaindustry/markov/natural_small_tree.xml build/markov-validation
    if ($LASTEXITCODE -ne 0) { throw 'Reference benchmark failed' }
    javac -d build/markov-validation src/main/java/com/iridium126/createmanaindustry/worldgen/markov/DotNetRandom.java src/main/java/com/iridium126/createmanaindustry/worldgen/markov/MarkovModel.java scripts/markov/MarkovValidation.java
    if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed' }
    java -cp build/markov-validation MarkovValidation src/main/resources/data/createmanaindustry/markov/natural_small_tree.xml build/markov-validation
    if ($LASTEXITCODE -ne 0) { throw 'Correctness or performance gate failed' }
} finally { Pop-Location }
