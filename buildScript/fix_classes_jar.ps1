# Fix classes.jar to match v1.12.13 native library
# This script replaces the CommandClientHandler interface with the correct version

$ErrorActionPreference = "Stop"

$libsDir = "$PSScriptRoot\..\app\libs"
$aarFile = "$libsDir\libbox.aar"
$classesJar = "$libsDir\classes.jar"
$tempDir = "$libsDir\temp_fix"

Write-Host "Fixing classes.jar to match v1.12.13 native library..." -ForegroundColor Cyan

# Create temp directory
if (Test-Path $tempDir) {
    Remove-Item -Recurse -Force $tempDir
}
New-Item -ItemType Directory -Path $tempDir | Out-Null

# Extract classes.jar from AAR if not already extracted
if (-not (Test-Path $classesJar)) {
    Write-Host "Extracting classes.jar from libbox.aar..." -ForegroundColor Yellow
    Push-Location $libsDir
    jar xf libbox.aar classes.jar
    Pop-Location
}

# Extract classes.jar contents
Write-Host "Extracting classes.jar contents..." -ForegroundColor Yellow
Push-Location $tempDir
jar xf $classesJar
Pop-Location

# Create the fixed CommandClientHandler.java
$clientHandlerFile = "$tempDir\io\nekohasekai\libbox\CommandClientHandler.java"
$clientHandlerContent = @"
package io.nekohasekai.libbox;

/**
 * CommandClientHandler interface matching v1.12.13 native library.
 * This interface is used for command client communication.
 */
public interface CommandClientHandler {
    void connected();
    void disconnected(String message);
    void clearLogs();
    void writeLogs(StringIterator messageList);
    void writeStatus(StatusMessage message);
    void writeGroups(OutboundGroupIterator message);
    void initializeClashMode(StringIterator modeList, String currentMode);
    void updateClashMode(String newMode);
    void writeConnections(Connections message);
}
"@

Write-Host "Creating fixed CommandClientHandler.java..." -ForegroundColor Yellow
[System.IO.File]::WriteAllText($clientHandlerFile, $clientHandlerContent, [System.Text.Encoding]::ASCII)

# Create the fixed CommandServerHandler.java
$serverHandlerFile = "$tempDir\io\nekohasekai\libbox\CommandServerHandler.java"
$serverHandlerContent = @"
package io.nekohasekai.libbox;

/**
 * CommandServerHandler interface matching native library.
 */
public interface CommandServerHandler {
    void serviceReload() throws Exception;
    void postServiceClose();
    SystemProxyStatus getSystemProxyStatus();
    void setSystemProxyEnabled(boolean isEnabled) throws Exception;
}
"@

Write-Host "Creating fixed CommandServerHandler.java..." -ForegroundColor Yellow
[System.IO.File]::WriteAllText($serverHandlerFile, $serverHandlerContent, [System.Text.Encoding]::ASCII)

# Compile the fixed interfaces
Write-Host "Compiling fixed interfaces..." -ForegroundColor Yellow
$classpath = "$tempDir"
Push-Location $tempDir

# Find javac
$javacPath = Get-Command javac -ErrorAction SilentlyContinue
if (-not $javacPath) {
    # Try to find it in JAVA_HOME
    $javaHome = $env:JAVA_HOME
    if ($javaHome -and (Test-Path "$javaHome\bin\javac.exe")) {
        $javacPath = "$javaHome\bin\javac.exe"
    }
    else {
        Write-Host "FAIL: javac not found. Please ensure JDK is installed and in PATH." -ForegroundColor Red
        Pop-Location
        exit 1
    }
}

# Compile with the existing classes as classpath
& javac -cp $classpath -d $tempDir $clientHandlerFile $serverHandlerFile
if ($LASTEXITCODE -ne 0) {
    Write-Host "FAIL: Compilation failed" -ForegroundColor Red
    Pop-Location
    exit 1
}

# Remove the .java files
Remove-Item $clientHandlerFile
Remove-Item $serverHandlerFile

# Recreate classes.jar
Write-Host "Recreating classes.jar..." -ForegroundColor Yellow
Remove-Item $classesJar -ErrorAction SilentlyContinue
jar cf $classesJar -C $tempDir .

Pop-Location

# Update the AAR file with the new classes.jar
Write-Host "Updating libbox.aar..." -ForegroundColor Yellow
Push-Location $libsDir

# Extract all AAR contents
$aarTemp = "$libsDir\aar_temp"
if (Test-Path $aarTemp) {
    Remove-Item -Recurse -Force $aarTemp
}
New-Item -ItemType Directory -Path $aarTemp | Out-Null
Push-Location $aarTemp
jar xf $aarFile
Pop-Location

# Copy new classes.jar
Copy-Item $classesJar "$aarTemp\classes.jar" -Force

# Recreate AAR
Remove-Item $aarFile
Push-Location $aarTemp
jar cf $aarFile *
Pop-Location

# Cleanup
Remove-Item -Recurse -Force $aarTemp
Remove-Item -Recurse -Force $tempDir
Remove-Item $classesJar -ErrorAction SilentlyContinue

Pop-Location

Write-Host ""
Write-Host "SUCCESS: classes.jar has been fixed to match v1.12.13 native library!" -ForegroundColor Green
Write-Host "Next: Run './gradlew clean assembleRelease' to rebuild APK" -ForegroundColor Yellow