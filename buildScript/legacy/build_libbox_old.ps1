# Build script for slim libbox
# Builds arm64-v8a only version of sing-box Android library

param(
    [string]$Version = "1.12.13",
    [string]$OutputDir = "..\app\libs",
    [switch]$CleanBuild = $false
)

$ErrorActionPreference = "Stop"

# Add Go bin to PATH
$GOPATH = go env GOPATH
$env:PATH = "$env:PATH;$GOPATH\bin"

# Configuration
$SINGBOX_REPO = "https://github.com/SagerNet/sing-box.git"
$BUILD_DIR = "$PSScriptRoot\singbox-build"
$ANDROID_API = 21

# Slim build tags - remove unnecessary features to reduce size
# Full tags: with_gvisor,with_quic,with_wireguard,with_utls,with_reality_server,with_clash_api,with_ech,with_dhcp,with_v2ray_api,with_grpc
# Slim version keeps only commonly used features
$BUILD_TAGS = "with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api"

Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  Slim libbox Build Script v1.0" -ForegroundColor Cyan
Write-Host "  Target Version: $Version" -ForegroundColor Cyan
Write-Host "  Architecture: arm64-v8a" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""

# Check environment
Write-Host "[1/7] Checking build environment..." -ForegroundColor Yellow

# Check Go
try {
    $goVersion = go version
    Write-Host "  OK Go: $goVersion" -ForegroundColor Green
}
catch {
    Write-Host "  FAIL Go not installed" -ForegroundColor Red
    exit 1
}

# Check gomobile
try {
    $gomobilePath = (Get-Command gomobile -ErrorAction SilentlyContinue).Source
    if ($gomobilePath) {
        Write-Host "  OK gomobile: $gomobilePath" -ForegroundColor Green
    }
    else {
        throw "gomobile not found"
    }
}
catch {
    Write-Host "  ! gomobile not found, installing..." -ForegroundColor Yellow
    go install golang.org/x/mobile/cmd/gomobile@latest
    go install golang.org/x/mobile/cmd/gobind@latest
}

# Check Android SDK/NDK
$ANDROID_HOME = $env:ANDROID_HOME
$ANDROID_NDK_HOME = $env:ANDROID_NDK_HOME

if (-not $ANDROID_HOME) {
    $ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
}
if (-not $ANDROID_NDK_HOME) {
    # Try to find NDK
    $ndkDir = Get-ChildItem "$ANDROID_HOME\ndk" -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
    if ($ndkDir) {
        $ANDROID_NDK_HOME = $ndkDir.FullName
    }
}

if (-not (Test-Path $ANDROID_HOME)) {
    Write-Host "  FAIL Android SDK not found: $ANDROID_HOME" -ForegroundColor Red
    exit 1
}
Write-Host "  OK Android SDK: $ANDROID_HOME" -ForegroundColor Green

if (-not (Test-Path $ANDROID_NDK_HOME)) {
    Write-Host "  FAIL Android NDK not found: $ANDROID_NDK_HOME" -ForegroundColor Red
    exit 1
}
Write-Host "  OK Android NDK: $ANDROID_NDK_HOME" -ForegroundColor Green

# Set environment variables
$env:ANDROID_HOME = $ANDROID_HOME
$env:ANDROID_NDK_HOME = $ANDROID_NDK_HOME

# Clone or update sing-box source
Write-Host ""
Write-Host "[2/7] Preparing sing-box source..." -ForegroundColor Yellow

if ($CleanBuild -and (Test-Path $BUILD_DIR)) {
    Write-Host "  Cleaning old build directory..." -ForegroundColor Gray
    Remove-Item -Recurse -Force $BUILD_DIR
}

if (-not (Test-Path $BUILD_DIR)) {
    Write-Host "  Cloning sing-box repository..." -ForegroundColor Gray
    git clone --depth 1 --branch "v$Version" $SINGBOX_REPO $BUILD_DIR
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  FAIL Clone failed" -ForegroundColor Red
        exit 1
    }
}
else {
    Write-Host "  Updating existing repository..." -ForegroundColor Gray
    Push-Location $BUILD_DIR
    git fetch --tags
    git checkout "v$Version"
    Pop-Location
}

Write-Host "  OK Source ready" -ForegroundColor Green

# Initialize gomobile
Write-Host ""
Write-Host "[3/7] Initializing gomobile..." -ForegroundColor Yellow
Push-Location $BUILD_DIR
gomobile init
if ($LASTEXITCODE -ne 0) {
    Write-Host "  FAIL gomobile init failed" -ForegroundColor Red
    Pop-Location
    exit 1
}
Write-Host "  OK gomobile initialized" -ForegroundColor Green

# Download dependencies
Write-Host ""
Write-Host "[4/7] Downloading Go dependencies..." -ForegroundColor Yellow
go mod download
if ($LASTEXITCODE -ne 0) {
    Write-Host "  FAIL Dependency download failed" -ForegroundColor Red
    Pop-Location
    exit 1
}

# Get gomobile bind package
Write-Host "  Getting gomobile bind package..." -ForegroundColor Gray
go get golang.org/x/mobile/bind
if ($LASTEXITCODE -ne 0) {
    Write-Host "  WARN Could not get bind package, trying alternative..." -ForegroundColor Yellow
}

Write-Host "  OK Dependencies downloaded" -ForegroundColor Green

# Build libbox
Write-Host ""
Write-Host "[5/7] Building libbox (slim version)..." -ForegroundColor Yellow
Write-Host "  Build options:" -ForegroundColor Gray
Write-Host "    - Architecture: arm64-v8a" -ForegroundColor Gray
Write-Host "    - API Level: $ANDROID_API" -ForegroundColor Gray
Write-Host "    - Tags: $BUILD_TAGS" -ForegroundColor Gray
Write-Host "    - Optimization: -ldflags='-s -w' (strip debug symbols)" -ForegroundColor Gray
Write-Host ""
Write-Host "  Starting build (this may take a few minutes)..." -ForegroundColor Gray

$startTime = Get-Date

# Use slim tags and optimization parameters
$env:CGO_ENABLED = 1
gomobile bind -v -androidapi $ANDROID_API -target "android/arm64" -tags "$BUILD_TAGS" -trimpath -ldflags "-s -w -buildid=" -o "libbox.aar" ./experimental/libbox

if ($LASTEXITCODE -ne 0) {
    Write-Host "  FAIL Build failed" -ForegroundColor Red
    Pop-Location
    exit 1
}

$endTime = Get-Date
$duration = $endTime - $startTime
Write-Host "  OK Build completed (time: $($duration.Minutes)m$($duration.Seconds)s)" -ForegroundColor Green

# Check output file size
$aarFile = Get-Item "libbox.aar"
$sizeMB = [math]::Round($aarFile.Length / 1MB, 2)
Write-Host "  OK Generated libbox.aar: $sizeMB MB" -ForegroundColor Green

# Copy to target directory
Write-Host ""
Write-Host "[6/7] Copying to project directory..." -ForegroundColor Yellow
$targetPath = Resolve-Path $OutputDir -ErrorAction SilentlyContinue
if (-not $targetPath) {
    $targetPath = "$PSScriptRoot\..\app\libs"
    New-Item -ItemType Directory -Force -Path $targetPath | Out-Null
}

# Backup existing aar
$existingAar = Join-Path $targetPath "libbox.aar"
if (Test-Path $existingAar) {
    $backupName = "libbox.aar.backup.$(Get-Date -Format 'yyyyMMdd-HHmmss')"
    Rename-Item $existingAar (Join-Path $targetPath $backupName)
    Write-Host "  Backed up existing aar as: $backupName" -ForegroundColor Gray
}

Copy-Item "libbox.aar" $existingAar -Force
Write-Host "  OK Copied to: $existingAar" -ForegroundColor Green

Pop-Location

# Clean Gradle cache
Write-Host ""
Write-Host "[7/7] Cleaning build cache..." -ForegroundColor Yellow
$strippedLibsDir = "$PSScriptRoot\..\app\build\stripped-libs"
if (Test-Path $strippedLibsDir) {
    Remove-Item -Recurse -Force $strippedLibsDir
    Write-Host "  OK Cleaned stripped-libs cache" -ForegroundColor Green
}

Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  Build Complete!" -ForegroundColor Cyan
Write-Host "  libbox.aar size: $sizeMB MB" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Next: Run './gradlew assembleRelease' to rebuild APK" -ForegroundColor Yellow