[CmdletBinding()]
param(
    [ValidateSet('amd64')]
    [string]$Architecture = 'amd64'
)

$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$repositoryDirectory = Split-Path -Parent $projectDirectory
$sourceDirectory = Join-Path $repositoryDirectory 'core\sing-box'
$runtimeDirectory = Join-Path $projectDirectory 'runtime\windows-x64'
$executable = Join-Path $runtimeDirectory 'sing-box.exe'
$cronetLibrary = Join-Path $runtimeDirectory 'libcronet.dll'
$expectedRevision = '1ac1a339cb1223e9c70eae14c44411c75033c02d'
$version = '1.14.1'
$goToolchain = 'go1.25.5'
$buildTags = 'with_gvisor,with_quic,with_dhcp,with_wireguard,with_utls,with_acme,with_clash_api,with_tailscale,with_ccm,with_ocm,with_naive_outbound,with_purego,badlinkname,tfogo_checklinkname0'
$linkerFlags = "-s -buildid= -X github.com/sagernet/sing-box/constant.Version=$version -X internal/godebug.defaultGODEBUG=multipathtcp=0 -checklinkname=0"

if (-not (Test-Path -LiteralPath (Join-Path $sourceDirectory 'go.mod'))) {
    throw 'sing-box source is missing. Run git submodule update --init --recursive.'
}

$actualRevision = (& git -C $sourceDirectory rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $actualRevision -ne $expectedRevision) {
    throw "sing-box revision mismatch. Expected $expectedRevision, got $actualRevision."
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
    # This tag requires Go 1.25.5. Go 1.26 removed an
    # internal poll symbol still referenced by the pinned tfo-go dependency.
    $env:GOTOOLCHAIN = $goToolchain

    # Use the source directory as the real process CWD. Go's -C flag can stamp
    # the outer repository revision into a binary built from a submodule.
    Push-Location $sourceDirectory
    try {
        & go build -buildvcs=false -trimpath -o $executable -tags $buildTags -ldflags $linkerFlags ./cmd/sing-box
        if ($LASTEXITCODE -ne 0) {
            throw "sing-box.exe build failed with exit code $LASTEXITCODE."
        }

        $cronetModule = 'github.com/sagernet/cronet-go/lib/windows_amd64'
        $cronetDirectory = (& go list -m -f '{{.Dir}}' $cronetModule).Trim()
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($cronetDirectory)) {
            throw 'Unable to resolve the Windows cronet-go library module.'
        }

        $cronetSource = Join-Path $cronetDirectory 'libcronet.dll'
        if (-not (Test-Path -LiteralPath $cronetSource)) {
            throw "The Windows cronet-go library is missing: $cronetSource"
        }
        Copy-Item -LiteralPath $cronetSource -Destination $cronetLibrary -Force
    }
    finally {
        Pop-Location
    }

    & $executable version
    if ($LASTEXITCODE -ne 0) {
        throw 'The built sing-box.exe failed its version health check.'
    }
}
finally {
    $env:CGO_ENABLED = $previousCgo
    $env:GOOS = $previousGoOs
    $env:GOARCH = $previousGoArch
    $env:GOTOOLCHAIN = $previousToolchain
}
