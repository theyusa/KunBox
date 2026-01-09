# Final Fix Build Script
# 1. Downloads/Uses Go 1.24.3 (Updated for sing-box 1.12+ compatibility)
# 2. Builds with correct package name (Fixes crash)
# 3. Builds with aggressive strip (Fixes size)
# 4. Auto-fetches latest stable version from GitHub if not specified

param(
    [string]$Version = "",  # Empty = auto-fetch latest
    [string]$OutputDir = "$PSScriptRoot\..\..\app\libs",
    [switch]$UseLatest = $true  # Default to using latest version
)

$ErrorActionPreference = "Stop"

# Fix Chinese encoding issues in PowerShell
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
chcp 65001 | Out-Null

# Go 版本配置 (集中管理以便后续升级)
# sing-box 1.12.15 要求 go1.24.11 toolchain (2025-12-02 已正式发布)
$GO_VERSION = "1.24.11"
$GO_DOWNLOAD_URL = "https://go.dev/dl/go$GO_VERSION.windows-amd64.zip"

$CacheDir = Join-Path $env:TEMP "SingBoxBuildCache_Fixed"
$GoZipPath = Join-Path $CacheDir "go$GO_VERSION.zip"
$GoExtractPath = Join-Path $CacheDir "go_extract"
$GoRoot = Join-Path $GoExtractPath "go"
$GoBin = Join-Path $GoRoot "bin"

Write-Host "[1/7] Setting up workspace..." -ForegroundColor Yellow
if (-not (Test-Path $CacheDir)) { New-Item -ItemType Directory -Force -Path $CacheDir | Out-Null }

# Auto-fetch latest version if not specified
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
        Write-Host "[2/7] Downloading Go $GO_VERSION (Required for sing-box 1.12+ compatibility)..." -ForegroundColor Yellow
        try {
            Invoke-WebRequest -Uri $GO_DOWNLOAD_URL -OutFile $GoZipPath
        }
        catch {
            Write-Host "Download failed. Please check network connection or manually download from: $GO_DOWNLOAD_URL" -ForegroundColor Red
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

# 关键:启用 Go toolchain 自动下载 (允许自动下载 go1.24.11)
$env:GOTOOLCHAIN = "auto"
Write-Host "  GOTOOLCHAIN=auto (允许自动升级到 go1.24.11)" -ForegroundColor Cyan

# Fix NDK Path - Auto detect or use explicit valid version
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

# 清理可能损坏的 gomobile 缓存
$GomobilePkg = Join-Path $env:GOPATH "pkg\gomobile"
if (Test-Path $GomobilePkg) {
    Write-Host "  Cleaning gomobile cache..." -ForegroundColor Gray
    Remove-Item -Recurse -Force $GomobilePkg -ErrorAction SilentlyContinue
}

# 关键:使用 SagerNet 修改版的 gomobile (修复 Go 1.24 兼容性)
Write-Host "  Installing gomobile..." -ForegroundColor Cyan
go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.8
if ($LASTEXITCODE -ne 0) { throw "Failed to install gomobile" }

Write-Host "  Installing gobind..." -ForegroundColor Cyan
go install github.com/sagernet/gomobile/cmd/gobind@v0.1.8
if ($LASTEXITCODE -ne 0) { throw "Failed to install gobind" }

Write-Host "  Initializing gomobile (this may take a while)..." -ForegroundColor Cyan
gomobile init -v
if ($LASTEXITCODE -ne 0) {
    Write-Host "  gomobile init failed, retrying..." -ForegroundColor Yellow
    gomobile init -v
    if ($LASTEXITCODE -ne 0) { throw "Failed to initialize gomobile after retry" }
}

# 5. Clone/Update Source - Always fetch the target version
Write-Host "[5/7] Preparing Source (v$Version)..." -ForegroundColor Yellow
$LocalSource = Join-Path $PSScriptRoot "..\singbox-build"
if (Test-Path $LocalSource) {
    Write-Host "Using local source code from $LocalSource" -ForegroundColor Cyan
    $BuildDir = $LocalSource
}
else {
    $BuildDir = Join-Path $CacheDir "singbox-source-v$Version"
    
    # Clean up old source directories if building a different version
    $OldSources = Get-ChildItem -Path $CacheDir -Directory -Filter "singbox-source-*" | Where-Object { $_.Name -ne "singbox-source-v$Version" }
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
}
Push-Location $BuildDir

# Fix deps in source (不再需要,官方构建工具会处理)
# go mod tidy

# 6. Build
Write-Host "[6/7] Building kernel..." -ForegroundColor Yellow
Write-Host "Using official sing-box build tool for version $Version..." -ForegroundColor Yellow

# 关键:使用 sing-box 官方构建工具 (修复 Go 1.24 兼容性问题)
# 不再直接调用 gomobile bind,而是通过官方 build_libbox 工具
go run ./cmd/internal/build_libbox -target android

if ($LASTEXITCODE -eq 0) {
    Write-Host "[7/7] Build Success! Updating project..." -ForegroundColor Green
    $Dest = $OutputDir
    if (-not (Test-Path $Dest)) { New-Item -ItemType Directory -Force -Path $Dest | Out-Null }
    Copy-Item "libbox.aar" (Join-Path $Dest "libbox.aar") -Force
    Write-Host "Updated libbox.aar at $Dest" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "================================================" -ForegroundColor Green
    Write-Host "  sing-box v$Version built successfully!" -ForegroundColor Green
    Write-Host "  Output: $Dest\libbox.aar" -ForegroundColor Green
    Write-Host "================================================" -ForegroundColor Green
}
else {
    Write-Host "Build failed." -ForegroundColor Red
}

Pop-Location