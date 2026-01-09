$env:PATH = "C:\Users\33039\AppData\Local\Temp\SingBoxBuildCache_Fixed\go_extract\go\bin;" + $env:PATH
$env:GOPATH = "C:\Users\33039\AppData\Local\Temp\SingBoxBuildCache_Fixed\gopath"
$env:PATH = $env:GOPATH + "\bin;" + $env:PATH
$env:ANDROID_NDK_HOME = "C:\Users\33039\AppData\Local\Android\Sdk\ndk\29.0.14206865"
$env:GOTOOLCHAIN = "auto"

Set-Location "C:\Users\33039\AppData\Local\Temp\SingBoxBuildCache_Fixed\singbox-source-v1.12.15"

Write-Host "Installing SagerNet gomobile..." -ForegroundColor Yellow
go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.8
go install github.com/sagernet/gomobile/cmd/gobind@v0.1.8
gomobile init

Write-Host "Running official build tool..." -ForegroundColor Yellow
go run ./cmd/internal/build_libbox -target android
