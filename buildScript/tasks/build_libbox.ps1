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

# Critical: Enable Go toolchain auto-download (allows auto-upgrade to go1.24.11)
$env:GOTOOLCHAIN = "auto"
Write-Host "  GOTOOLCHAIN=auto (Allow auto-upgrade to go1.24.11)" -ForegroundColor Cyan

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
& "$GoBin\go.exe" install github.com/sagernet/gomobile/cmd/gomobile@v0.1.8
if ($LASTEXITCODE -ne 0) { throw "Failed to install gomobile" }

Write-Host "  Installing gobind..." -ForegroundColor Cyan
& "$GoBin\go.exe" install github.com/sagernet/gomobile/cmd/gobind@v0.1.8
if ($LASTEXITCODE -ne 0) { throw "Failed to install gobind" }

# 验证安装
$GomobileBin = Join-Path $env:GOPATH "bin\gomobile.exe"
$GobindBin = Join-Path $env:GOPATH "bin\gobind.exe"
if (-not (Test-Path $GomobileBin)) { throw "gomobile.exe not found at $GomobileBin" }
if (-not (Test-Path $GobindBin)) { throw "gobind.exe not found at $GobindBin" }

Write-Host "  Initializing gomobile (this may take a while, typically 2-5 minutes)..." -ForegroundColor Cyan
Write-Host "  Note: This downloads and compiles Android toolchains..." -ForegroundColor Gray

# 使用完整路径调用 gomobile，避免 PATH 问题
# 关键修复: 设置 GOROOT 环境变量，让 gomobile 能找到 go 命令
$env:GOROOT = $GoRoot
& $GomobileBin init -v
if ($LASTEXITCODE -ne 0) {
    Write-Host "  gomobile init failed, retrying with NDK path..." -ForegroundColor Yellow
    & $GomobileBin init -ndk $env:ANDROID_NDK_HOME -v
    if ($LASTEXITCODE -ne 0) { throw "Failed to initialize gomobile after retry" }
}

Write-Host "  Verifying gomobile installation..." -ForegroundColor Cyan
# 跳过缓存检查，gomobile init 成功即可继续
Write-Host "  gomobile init completed, proceeding..." -ForegroundColor Green

# 5. Clone/Update Source - Always fetch the target version
Write-Host "[5/7] Preparing Source (v$Version)..." -ForegroundColor Yellow
$ExtensionDir = Join-Path $PSScriptRoot "..\singbox-build"
$BuildDir = Join-Path $CacheDir "singbox-source-v$Version"

# Clean up old source directories if building a different version
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

# Inject KunBox extensions (ResetAllConnections, etc.)
$BoxExtFile = Join-Path $ExtensionDir "box_ext.go"
if (Test-Path $BoxExtFile) {
    $LibboxDir = Join-Path $BuildDir "experimental\libbox"
    if (Test-Path $LibboxDir) {
        $DestFile = Join-Path $LibboxDir "box_ext.go"
        Copy-Item $BoxExtFile $DestFile -Force
        Write-Host "Injected KunBox extension: box_ext.go -> experimental/libbox/" -ForegroundColor Cyan
    }
    else {
        Write-Host "Warning: libbox directory not found at $LibboxDir, skipping extension injection" -ForegroundColor Yellow
    }
}
else {
    Write-Host "No extension file found at $BoxExtFile" -ForegroundColor Gray
}
Push-Location $BuildDir

# Fix deps in source (不再需要,官方构建工具会处理)
# go mod tidy

# 6. Build
Write-Host "[6/7] Building kernel..." -ForegroundColor Yellow
Write-Host "Using official sing-box build tool for version $Version..." -ForegroundColor Yellow
Write-Host "Optimization: Building arm64-v8a only..." -ForegroundColor Cyan

# 关键修复: 确保所有环境变量都正确设置，包括 GOPATH/bin 在 PATH 中
# 这样 go run 启动的子进程也能找到 gomobile 和 gobind
$TempDir = [System.IO.Path]::GetTempPath()
$CacheDirPath = Join-Path $TempDir "SingBoxBuildCache_Fixed"
$GoPathPath = Join-Path $CacheDirPath "gopath"
$GoRootPath = Join-Path $CacheDirPath "go_extract\go"
$GoBinPath = Join-Path $GoRootPath "bin"

$env:GOROOT = $GoRootPath
$env:GOPATH = $GoPathPath
$env:PATH = "$GoBinPath;$GoPathPath\bin;$env:PATH"
$env:GOTOOLCHAIN = "auto"

Write-Host "Environment configured: GOROOT=$GoRootPath" -ForegroundColor Gray

# 编译优化参数 (2026-01-09 添加)
Write-Host "  Applying size optimization flags..." -ForegroundColor Cyan

# CGO 编译器优化 (优先体积而非速度)
$env:CGO_CFLAGS = "-Os -ffunction-sections -fdata-sections -fvisibility=hidden"
$env:CGO_LDFLAGS = "-Wl,--gc-sections -Wl,--strip-all -Wl,--as-needed"

# Go 编译器优化 (通过 GOFLAGS 环境变量传递)
$env:GOFLAGS = "-trimpath -buildvcs=false -ldflags=-s -ldflags=-w"

Write-Host "    CGO_CFLAGS: $env:CGO_CFLAGS" -ForegroundColor Gray
Write-Host "    CGO_LDFLAGS: $env:CGO_LDFLAGS" -ForegroundColor Gray
Write-Host "    GOFLAGS: $env:GOFLAGS" -ForegroundColor Gray
Write-Host "    优化策略: 体积优先 (-Os)" -ForegroundColor Cyan
Write-Host "    注意: sing-box 官方构建工具不支持裁剪协议,构建完整内核" -ForegroundColor Yellow

# 关键:使用 sing-box 官方构建工具 (修复 Go 1.24 兼容性问题)
# 优化:仅构建 arm64-v8a 架构 (覆盖 99% 现代设备,减少 75% 体积)
# 如需支持旧设备,可改为: android/arm64,android/arm
# 注意: sing-box 构建工具内部已包含 -s -w 优化参数 (见 cmd/internal/build_libbox/main.go)

# 修复: 使用 -platform 参数而不是 -target (sing-box 1.12+ 的正确参数名)
Write-Host ""
Write-Host "Executing: go run ./cmd/internal/build_libbox -target android -platform android/arm64" -ForegroundColor Gray
Write-Host "  With optimization: CGO_CFLAGS/LDFLAGS + GOFLAGS" -ForegroundColor Gray
Write-Host "This will take several minutes (typically 5-15 minutes)..." -ForegroundColor Gray
Write-Host ""

# 使用完整路径的 go 命令,确保环境变量正确传递
# sing-box 构建工具已内置 -s -w 和其他优化参数
# 启用完整协议支持 (with_wireguard 等)
$BUILD_TAGS = "with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api"
Write-Host "  Build tags: $BUILD_TAGS" -ForegroundColor Cyan

& "$GoBin\go.exe" run -tags "$BUILD_TAGS" ./cmd/internal/build_libbox -target android -platform android/arm64

if ($LASTEXITCODE -eq 0) {
    Write-Host ""
    Write-Host "[7/7] Checking build output..." -ForegroundColor Yellow

    # 检查 libbox.aar 是否生成
    if (-not (Test-Path "libbox.aar")) {
        Write-Host "Error: libbox.aar not found in build directory!" -ForegroundColor Red
        Write-Host "Build may have failed silently. Please check the output above for errors." -ForegroundColor Red
        Pop-Location
        exit 1
    }

    $AarSize = (Get-Item "libbox.aar").Length / 1MB
    Write-Host "Build Success! Generated libbox.aar (${AarSize:N2} MB)" -ForegroundColor Green

    Write-Host "Updating project..." -ForegroundColor Yellow
    $Dest = $OutputDir
    if (-not (Test-Path $Dest)) { New-Item -ItemType Directory -Force -Path $Dest | Out-Null }
    Copy-Item "libbox.aar" (Join-Path $Dest "libbox.aar") -Force
    Write-Host "Updated libbox.aar at $Dest" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "================================================" -ForegroundColor Green
    Write-Host "  sing-box v$Version built successfully!" -ForegroundColor Green
    Write-Host "  Output: $Dest\libbox.aar (${AarSize:N2} MB)" -ForegroundColor Green
    Write-Host "  Target: arm64-v8a only" -ForegroundColor Green
    Write-Host "================================================" -ForegroundColor Green
}
else {
    Write-Host ""
    Write-Host "Build failed with exit code: $LASTEXITCODE" -ForegroundColor Red
    Write-Host "Please check the error messages above." -ForegroundColor Red
}

Pop-Location