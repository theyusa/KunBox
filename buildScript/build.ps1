# Main Build Script for SingBox Android
# Wrapper to run specific tasks

$ErrorActionPreference = "Stop"

function Show-Menu {
    Clear-Host
    Write-Host "============================================" -ForegroundColor Cyan
    Write-Host "  SingBox Android Build Manager" -ForegroundColor Cyan
    Write-Host "============================================" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "1. Build libbox (Full Build)" -ForegroundColor Yellow
    Write-Host "   - Downloads Go, setups environment, builds AAR"
    Write-Host ""
    Write-Host "2. Fix classes.jar" -ForegroundColor Yellow
    Write-Host "   - Patches interface issues in generated AAR"
    Write-Host ""
    Write-Host "3. Extract Kernel from NekoBox" -ForegroundColor Yellow
    Write-Host "   - Extracts optimized .so from NekoBox APK"
    Write-Host ""
    Write-Host "4. Inspect AAR Symbols" -ForegroundColor Yellow
    Write-Host "   - Checks JNI symbols in generated AAR"
    Write-Host ""
    Write-Host "Q. Quit" -ForegroundColor Gray
    Write-Host ""
}

while ($true) {
    Show-Menu
    $choice = Read-Host "Select a task (1-4, Q)"

    switch ($choice) {
        "1" {
            Write-Host "Starting Build..." -ForegroundColor Cyan
            & "$PSScriptRoot\tasks\build_libbox.ps1"
            Pause
        }
        "2" {
            Write-Host "Fixing classes.jar..." -ForegroundColor Cyan
            & "$PSScriptRoot\tasks\fix_classes.ps1"
            Pause
        }
        "3" {
            Write-Host "Extracting Kernel..." -ForegroundColor Cyan
            & "$PSScriptRoot\tasks\extract_kernel.ps1"
            Pause
        }
        "4" {
            Write-Host "Inspecting Symbols..." -ForegroundColor Cyan
            & "$PSScriptRoot\tasks\inspect_symbols.ps1"
            Pause
        }
        "Q" {
            exit
        }
        "q" {
            exit
        }
        Default {
            Write-Host "Invalid selection. Press any key to try again..." -ForegroundColor Red
            [Console]::ReadKey() | Out-Null
        }
    }
}