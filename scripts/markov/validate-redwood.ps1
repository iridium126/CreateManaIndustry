param([int[]]$Seeds = @(137, 0, -1, [int]::MinValue, [int]::MaxValue), [switch]$Preview)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location $repo
try {
    New-Item -ItemType Directory -Force build/redwood-validation | Out-Null
    dotnet build scripts/markov/RedwoodUpstream.csproj -c Release -o build/redwood-upstream
    if ($LASTEXITCODE -ne 0) { throw 'Upstream build failed' }
    dotnet build scripts/markov/RedwoodReference.csproj -c Release -o build/redwood-reference
    if ($LASTEXITCODE -ne 0) { throw 'Reference harness build failed' }
    $sources = @('DotNetRandom', 'MarkovModel', 'RedwoodVolume', 'RedwoodRefinement', 'RedwoodBotany', 'EpicRedwoodModel') | ForEach-Object {
        "src/main/java/com/iridium126/createmanaindustry/worldgen/markov/$_.java"
    }
    javac -d build/redwood-validation @sources scripts/markov/RedwoodValidation.java scripts/markov/RedwoodPreview.java
    if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed' }
    $model = 'src/main/resources/data/createmanaindustry/markov/epic_redwood_3072.xml'
    foreach ($seed in $Seeds) {
        $reference = "build/redwood-validation/reference-$seed.gz"
        dotnet build/redwood-reference/RedwoodReference.dll build/redwood-upstream/RedwoodUpstream.dll $model $seed $reference
        if ($LASTEXITCODE -ne 0) { throw "Reference failed for seed $seed" }
        java -Xmx512m -cp build/redwood-validation RedwoodValidation $model $seed $reference
        if ($LASTEXITCODE -ne 0) { throw "Voxel comparison failed for seed $seed" }
        if ($Preview) {
            java -Xmx512m -cp build/redwood-validation RedwoodPreview $model $seed "build/redwood-preview/$seed"
            if ($LASTEXITCODE -ne 0) { throw "Preview failed for seed $seed" }
        }
    }
} finally { Pop-Location }
