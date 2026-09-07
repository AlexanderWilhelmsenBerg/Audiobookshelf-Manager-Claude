param(
    [string]$LoopboundRoot = (Join-Path $PSScriptRoot "..\..\Loopbound")
)

$ErrorActionPreference = "Stop"

function Assert-LastExitCode([string]$Step) {
    if ($LASTEXITCODE -ne 0) {
        throw "$Step failed with exit code $LASTEXITCODE."
    }
}

$BookWaveRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$LoopboundRoot = (Resolve-Path $LoopboundRoot).Path
$Destination = Join-Path $BookWaveRoot "app\src\main\assets\loopbound"
$Dist = Join-Path $LoopboundRoot "dist"

Push-Location $LoopboundRoot
try {
    & npm ci
    Assert-LastExitCode "npm ci"

    & npm run validate:content
    Assert-LastExitCode "Loopbound content validation"

    & npm test
    Assert-LastExitCode "Loopbound tests"

    # Loopbound's build script performs its strict TypeScript check and then creates the Vite dist bundle.
    & npm run build
    Assert-LastExitCode "Loopbound production build"

    $LoopboundCommit = (& git rev-parse HEAD).Trim()
    Assert-LastExitCode "Reading the Loopbound commit"
} finally {
    Pop-Location
}

$EntryPoint = Join-Path $Dist "index.html"
if (-not (Test-Path $EntryPoint)) {
    throw "Loopbound build completed without dist\index.html."
}

Remove-Item $Destination -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $Destination -Force | Out-Null
Copy-Item (Join-Path $Dist "*") $Destination -Recurse -Force
Set-Content -Path (Join-Path $Destination "BOOKWAVE_LOOPBOUND_VERSION") -Value $LoopboundCommit -NoNewline

Write-Host "Bundled Loopbound $LoopboundCommit into $Destination"
Write-Host "The destination is gitignored; build BookWave normally to package this snapshot into the APK/AAB."
