function Get-CmiArtifact([string]$RepoPath) {
    $versionLine = Get-Content -LiteralPath (Join-Path $RepoPath 'gradle.properties') |
        Where-Object { $_.StartsWith('mod_version=') } | Select-Object -First 1
    if (-not $versionLine) { throw 'Could not read mod_version from gradle.properties.' }

    $version = $versionLine.Substring('mod_version='.Length).Trim()
    $artifact = Join-Path $RepoPath "build/libs/createmanaindustry-$version.jar"
    if (-not (Test-Path -LiteralPath $artifact -PathType Leaf)) { throw "Build the CMI jar first: $artifact" }
    return $artifact
}

