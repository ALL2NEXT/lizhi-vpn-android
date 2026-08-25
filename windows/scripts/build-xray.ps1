[CmdletBinding()]
param(
    [ValidateSet('amd64')]
    [string]$Architecture = 'amd64'
)

$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$repositoryDirectory = Split-Path -Parent $projectDirectory
$sourceDirectory = Join-Path $repositoryDirectory 'xray-core'
$runtimeDirectory = Join-Path $projectDirectory 'runtime\windows-x64'
$executable = Join-Path $runtimeDirectory 'xray.exe'
$expectedRevision = 'd2758a023cd7f4174a5a5fa4ff66e487d4342ba0'
$goToolchain = 'go1.26.5'
$linkerFlags = '-s -w -buildid='

if (-not (Test-Path -LiteralPath (Join-Path $sourceDirectory 'go.mod'))) {
    throw 'Xray-core source is missing. Run git submodule update --init --recursive.'
}

$actualRevision = (& git -C $sourceDirectory rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $actualRevision -ne $expectedRevision) {
    throw "Xray-core revision mismatch. Expected $expectedRevision, got $actualRevision."
}

New-Item -ItemType Directory -Path $runtimeDirectory -Force | Out-Null

$previousCgo = $env:CGO_ENABLED
$previousGoOs = $env:GOOS
$previousGoArch = $env:GOARCH
$previousToolchain = $env:GOTOOLCHAIN

try {
    $env:CGO_ENABLED = '0'
    $env:GOOS = 'windows'
    $env:GOARCH = $Architecture
    $env:GOTOOLCHAIN = $goToolchain

    Push-Location $sourceDirectory
    try {
        & go build -buildvcs=false -trimpath -o $executable -ldflags $linkerFlags ./main
        if ($LASTEXITCODE -ne 0) {
            throw "xray.exe build failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }

    & $executable version
    if ($LASTEXITCODE -ne 0) {
        throw 'The built xray.exe failed its version health check.'
    }
}
finally {
    $env:CGO_ENABLED = $previousCgo
    $env:GOOS = $previousGoOs
    $env:GOARCH = $previousGoArch
    $env:GOTOOLCHAIN = $previousToolchain
}
