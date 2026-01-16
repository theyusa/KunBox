# NekoBox-style Build Script for sing-box libbox.aar
# Optimized for smaller binary size using direct gomobile bind

param(
    [string]$Version = "",
    [string]$OutputDir = "$PSScriptRoot\..\..\app\libs",
    [switch]$UseLatest = $true
)

$ErrorActionPreference = "Stop"

# Fix Chinese encoding
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
chcp 65001 | Out-Null

# Go version config
$GO_VERSION = "1.24.11"
$GO_DOWNLOAD_URL = "https://go.dev/dl/go$GO_VERSION.windows-amd64.zip"

$CacheDir = Join-Path $env:TEMP "SingBoxBuildCache_Fixed"
$GoZipPath = Join-Path $CacheDir "go$GO_VERSION.zip"
$GoExtractPath = Join-Path $CacheDir "go_extract"
$GoRoot = Join-Path $GoExtractPath "go"
$GoBin = Join-Path $GoRoot "bin"

Write-Host "[1/7] Setting up workspace..." -ForegroundColor Yellow
if (-not (Test-Path $CacheDir)) { New-Item -ItemType Directory -Force -Path $CacheDir | Out-Null }

# Auto-fetch latest version
if ([string]::IsNullOrEmpty($Version) -or $UseLatest) {
    Write-Host "Fetching latest stable version from GitHub..." -ForegroundColor Yellow
    try {
        $releaseInfo = Invoke-RestMethod -Uri "https://api.github.com/repos/SagerNet/sing-box/releases/latest" -Headers @{ "User-Agent" = "PowerShell" }
        $Version = $releaseInfo.tag_name -replace '^v', ''
        Write-Host "Latest stable version: $Version" -ForegroundColor Green
    }
    catch {
        Write-Host "Failed to fetch, forcing v1.12.15" -ForegroundColor Yellow
        $Version = "1.12.15"
    }
}

# 2. Check/Download Go
if (-not (Test-Path "$GoBin\go.exe")) {
    if (-not (Test-Path $GoZipPath)) {
        Write-Host "[2/7] Downloading Go $GO_VERSION..." -ForegroundColor Yellow
        try {
            Invoke-WebRequest -Uri $GO_DOWNLOAD_URL -OutFile $GoZipPath
        }
        catch {
            Write-Host "Download failed: $GO_DOWNLOAD_URL" -ForegroundColor Red
            exit 1
        }
    }
    else {
        Write-Host "[2/7] Found cached Go $GO_VERSION zip..." -ForegroundColor Green
    }
    
    Write-Host "Extracting Go..." -ForegroundColor Yellow
    if (Test-Path $GoExtractPath) { Remove-Item -Recurse -Force $GoExtractPath }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::ExtractToDirectory($GoZipPath, $GoExtractPath)
}
else {
    Write-Host "[2/7] Using cached Go $GO_VERSION environment..." -ForegroundColor Green
}

# 3. Setup Env
Write-Host "[3/7] Configuring Environment..." -ForegroundColor Yellow
$env:GOROOT = $GoRoot
$env:PATH = "$GoBin;$env:PATH"
$env:GOPATH = Join-Path $CacheDir "gopath"
$env:PATH = "$env:PATH;$env:GOPATH\bin"
$env:GOTOOLCHAIN = "auto"

# Fix NDK Path
$SdkRoot = $env:ANDROID_SDK_ROOT
if (-not $SdkRoot) { $SdkRoot = $env:ANDROID_HOME }
if (-not $SdkRoot) { $SdkRoot = "C:\Users\$env:USERNAME\AppData\Local\Android\Sdk" }

$NdkRoot = Join-Path $SdkRoot "ndk"
if (Test-Path $NdkRoot) {
    $LatestNdk = Get-ChildItem -Path $NdkRoot -Directory | Sort-Object Name -Descending | Select-Object -First 1
    if ($LatestNdk) {
        Write-Host "Setting ANDROID_NDK_HOME to $($LatestNdk.FullName)" -ForegroundColor Cyan
        $env:ANDROID_NDK_HOME = $LatestNdk.FullName
    }
}
if (-not $env:ANDROID_NDK_HOME) {
    Write-Warning "NDK not found. Please install Android NDK."
}

# 4. Install Tools
Write-Host "[4/7] Installing build tools..." -ForegroundColor Yellow

$GomobilePkg = Join-Path $env:GOPATH "pkg\gomobile"
if (Test-Path $GomobilePkg) {
    Write-Host "  Cleaning gomobile cache..." -ForegroundColor Gray
    Remove-Item -Recurse -Force $GomobilePkg -ErrorAction SilentlyContinue
}

Write-Host "  Installing gomobile..." -ForegroundColor Cyan
& "$GoBin\go.exe" install github.com/sagernet/gomobile/cmd/gomobile@v0.1.8
if ($LASTEXITCODE -ne 0) { throw "Failed to install gomobile" }

Write-Host "  Installing gobind..." -ForegroundColor Cyan
& "$GoBin\go.exe" install github.com/sagernet/gomobile/cmd/gobind@v0.1.8
if ($LASTEXITCODE -ne 0) { throw "Failed to install gobind" }

$GomobileBin = Join-Path $env:GOPATH "bin\gomobile.exe"
$GobindBin = Join-Path $env:GOPATH "bin\gobind.exe"
if (-not (Test-Path $GomobileBin)) { throw "gomobile.exe not found at $GomobileBin" }
if (-not (Test-Path $GobindBin)) { throw "gobind.exe not found at $GobindBin" }

Write-Host "  Initializing gomobile..." -ForegroundColor Cyan
$env:GOROOT = $GoRoot
& $GomobileBin init -v
if ($LASTEXITCODE -ne 0) {
    Write-Host "  gomobile init failed, retrying with NDK path..." -ForegroundColor Yellow
    & $GomobileBin init -ndk $env:ANDROID_NDK_HOME -v
    if ($LASTEXITCODE -ne 0) { throw "Failed to initialize gomobile after retry" }
}
Write-Host "  gomobile init completed" -ForegroundColor Green

# 5. Clone/Update Source
Write-Host "[5/7] Preparing Source v$Version..." -ForegroundColor Yellow
$ExtensionDir = Join-Path $PSScriptRoot "..\singbox-build"
$BuildDir = Join-Path $CacheDir "singbox-source-v$Version"

$OldSources = Get-ChildItem -Path $CacheDir -Directory -Filter "singbox-source-*" -ErrorAction SilentlyContinue | Where-Object { $_.Name -ne "singbox-source-v$Version" }
foreach ($old in $OldSources) {
    Write-Host "Removing old source: $($old.Name)" -ForegroundColor Gray
    Remove-Item -Recurse -Force $old.FullName
}

if (-not (Test-Path $BuildDir)) {
    Write-Host "Cloning sing-box v$Version from GitHub..." -ForegroundColor Yellow
    git clone --depth 1 --branch "v$Version" https://github.com/SagerNet/sing-box.git $BuildDir
}
else {
    Write-Host "Using cached source for v$Version" -ForegroundColor Green
}

# Inject KunBox extensions
$BoxExtFile = Join-Path $ExtensionDir "box_ext.go"
if (Test-Path $BoxExtFile) {
    $LibboxDir = Join-Path $BuildDir "experimental\libbox"
    if (Test-Path $LibboxDir) {
        $DestFile = Join-Path $LibboxDir "box_ext.go"
        Copy-Item $BoxExtFile $DestFile -Force
        Write-Host "Injected KunBox extension: box_ext.go" -ForegroundColor Cyan
    }
}

# Inject URLTest extension
$UrlTestExtFile = Join-Path $ExtensionDir "urltest_ext.go"
if (Test-Path $UrlTestExtFile) {
    $LibboxDir = Join-Path $BuildDir "experimental\libbox"
    if (Test-Path $LibboxDir) {
        $DestFile = Join-Path $LibboxDir "urltest_ext.go"
        Copy-Item $UrlTestExtFile $DestFile -Force
        Write-Host "Injected KunBox extension: urltest_ext.go" -ForegroundColor Cyan
    }
}

# Inject Fetch extension
$FetchExtFile = Join-Path $ExtensionDir "fetch_ext.go"
if (Test-Path $FetchExtFile) {
    $LibboxDir = Join-Path $BuildDir "experimental\libbox"
    if (Test-Path $LibboxDir) {
        $DestFile = Join-Path $LibboxDir "fetch_ext.go"
        Copy-Item $FetchExtFile $DestFile -Force
        Write-Host "Injected KunBox extension: fetch_ext.go" -ForegroundColor Cyan
    }
}

# Inject Reload extension (Hot Reload support)
$ReloadExtFile = Join-Path $ExtensionDir "reload_ext.go"
if (Test-Path $ReloadExtFile) {
    $LibboxDir = Join-Path $BuildDir "experimental\libbox"
    if (Test-Path $LibboxDir) {
        $DestFile = Join-Path $LibboxDir "reload_ext.go"
        Copy-Item $ReloadExtFile $DestFile -Force
        Write-Host "Injected KunBox extension: reload_ext.go" -ForegroundColor Cyan
    }
}

# Patch build_libbox to remove tailscale (NekoBox-style optimization)
$BuildLibboxFile = Join-Path $BuildDir "cmd\internal\build_libbox\main.go"
if (Test-Path $BuildLibboxFile) {
    $content = Get-Content $BuildLibboxFile -Raw
    # Remove tailscale from Android build by changing memcTags to empty for Android
    # Original: tags := append(sharedTags, memcTags...)
    # Changed:  tags := sharedTags (no memcTags for smaller binary)
    $content = $content -replace 'tags := append\(sharedTags, memcTags\.\.\.\)', 'tags := sharedTags // KunBox: removed tailscale for smaller binary'
    Set-Content $BuildLibboxFile -Value $content -NoNewline
    Write-Host "Patched build_libbox: removed tailscale from Android build" -ForegroundColor Cyan
}

Push-Location $BuildDir

# Run go mod tidy first to ensure dependencies are correct
Write-Host "  Running go mod tidy..." -ForegroundColor Gray
& "$GoBin\go.exe" mod tidy

# 6. Build
Write-Host "[6/7] Building kernel - NekoBox style..." -ForegroundColor Yellow
Write-Host "  Target: arm64-v8a only" -ForegroundColor Cyan

# Ensure PATH includes gomobile/gobind
$env:PATH = "$env:GOPATH\bin;$GoBin;$env:PATH"
Write-Host "  PATH updated to include gomobile/gobind" -ForegroundColor Gray

# CGO optimization
$env:CGO_CFLAGS = "-Os -ffunction-sections -fdata-sections -fvisibility=hidden"
$env:CGO_LDFLAGS = "-Wl,--gc-sections -Wl,--strip-all -Wl,--as-needed"

Write-Host "  CGO_CFLAGS: $env:CGO_CFLAGS" -ForegroundColor Gray
Write-Host "  CGO_LDFLAGS: $env:CGO_LDFLAGS" -ForegroundColor Gray

# NekoBox style build tags (smaller than official default)
# Removed: with_naive_outbound, with_tailscale (saves ~2-3MB)
# Added: with_conntrack (connection tracking)
$BUILD_TAGS = "with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api"
Write-Host "  Build tags: $BUILD_TAGS" -ForegroundColor Cyan
Write-Host "  Removed: with_naive_outbound, with_tailscale" -ForegroundColor Gray

Write-Host "  Executing official build tool..." -ForegroundColor Gray
Write-Host "  This will take 5-15 minutes..." -ForegroundColor Gray

# Use official sing-box build tool (fixes Go 1.24 compatibility)
# But with NekoBox-style reduced tags
& "$GoBin\go.exe" run "-tags=$BUILD_TAGS" ./cmd/internal/build_libbox -target android -platform android/arm64

$BuildExitCode = $LASTEXITCODE
$AarOutput = Join-Path $BuildDir "libbox.aar"

# 7. Check output
if ($BuildExitCode -eq 0 -and (Test-Path $AarOutput)) {
    Write-Host ""
    Write-Host "[7/7] Checking build output..." -ForegroundColor Yellow
    
    $AarSize = (Get-Item $AarOutput).Length / 1MB
    $AarSizeStr = [math]::Round($AarSize, 2)
    Write-Host "Build Success! Generated libbox.aar - $AarSizeStr MB" -ForegroundColor Green

    Write-Host "Updating project..." -ForegroundColor Yellow
    $Dest = $OutputDir
    if (-not (Test-Path $Dest)) { New-Item -ItemType Directory -Force -Path $Dest | Out-Null }
    Copy-Item $AarOutput (Join-Path $Dest "libbox.aar") -Force
    Write-Host "Updated libbox.aar at $Dest" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "================================================" -ForegroundColor Green
    Write-Host "  sing-box v$Version built successfully!" -ForegroundColor Green
    Write-Host "  Output: $AarSizeStr MB" -ForegroundColor Green
    Write-Host "  Target: arm64-v8a only - NekoBox-style" -ForegroundColor Green
    Write-Host "================================================" -ForegroundColor Green
}
else {
    Write-Host ""
    Write-Host "Build failed with exit code: $BuildExitCode" -ForegroundColor Red
    Write-Host "Please check the error messages above." -ForegroundColor Red
}

Pop-Location
