[CmdletBinding()]
param(
    [ValidateSet('amd64')]
    [string]$Architecture = 'amd64'
)

$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$repositoryDirectory = Split-Path -Parent $projectDirectory
$sourceDirectory = Join-Path $repositoryDirectory 'sing-box'
$runtimeDirectory = Join-Path $projectDirectory 'runtime\windows-x64'
$executable = Join-Path $runtimeDirectory 'sing-box.exe'
$cronetLibrary = Join-Path $runtimeDirectory 'libcronet.dll'
$expectedRevision = '58ccf82e0bfc5e724421d2ba03157fe7f0fee537'
$version = '1.13.0-rc.3'
$goToolchain = 'go1.25.4'
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
    # This tag's official Windows workflow uses Go 1.25.4. Go 1.26 removed an
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

        $cronetVersion = (& go list -m -f '{{.Version}}' github.com/sagernet/cronet-go).Trim()
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($cronetVersion)) {
            throw 'Unable to resolve the cronet-go version.'
        }

        & go run "github.com/sagernet/cronet-go/cmd/build-naive@$cronetVersion" extract-lib --target "windows/$Architecture" -o $runtimeDirectory
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $cronetLibrary)) {
            throw 'libcronet.dll extraction failed.'
        }
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
