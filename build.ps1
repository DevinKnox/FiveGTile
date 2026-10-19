[CmdletBinding()]
param(
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string]$JdkRoot = $env:JAVA_HOME,
    [string]$BuildDirectory,
    [string]$OutputApk,
    [string]$KeyStore,
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'

function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "执行失败（$LASTEXITCODE）：$Program" }
}

if (-not $SdkRoot) { $SdkRoot = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
if (-not $JdkRoot) { throw '请通过 -JdkRoot 指定 JDK 17 或更高版本。' }
$java = Join-Path $JdkRoot 'bin/java.exe'
$wrapper = Join-Path $PSScriptRoot 'gradle/wrapper/gradle-wrapper.jar'
foreach ($path in @($java, $wrapper)) {
    if (-not (Test-Path -LiteralPath $path)) { throw "缺少构建文件：$path" }
}
if (-not $BuildDirectory) {
    $BuildDirectory = Join-Path $PSScriptRoot ('.build/' + (Get-Date -Format 'yyyyMMdd-HHmmssfff'))
}
if (-not $OutputApk) { $OutputApk = Join-Path $PSScriptRoot 'FiveGTile-1.1.0-debug.apk' }
$BuildDirectory = [IO.Path]::GetFullPath($BuildDirectory)
$OutputApk = [IO.Path]::GetFullPath($OutputApk)
$gradleArguments = @("-Dorg.gradle.java.home=$JdkRoot", '-classpath', $wrapper,
    'org.gradle.wrapper.GradleWrapperMain', ':app:assembleDebug', '--no-daemon', '--console=plain',
    "-PfivegBuildDirectory=$BuildDirectory")
if ($Offline) { $gradleArguments += '--offline' }
if ($KeyStore) {
    if (-not (Test-Path -LiteralPath $KeyStore -PathType Leaf)) { throw "调试证书不存在：$KeyStore" }
    $gradleArguments += "-PfivegDebugKeystore=$([IO.Path]::GetFullPath($KeyStore))"
}

# 交给 Gradle 编译 Kotlin、打包标准库并签名；SDK 变量仅在本次构建进程中生效。
$previousAndroidHome = $env:ANDROID_HOME
Push-Location -LiteralPath $PSScriptRoot
try {
    $env:ANDROID_HOME = $SdkRoot
    Invoke-Checked $java $gradleArguments
} finally {
    $env:ANDROID_HOME = $previousAndroidHome
    Pop-Location
}
$builtApk = Join-Path $BuildDirectory 'outputs/apk/debug/app-debug.apk'
if (-not (Test-Path -LiteralPath $builtApk)) { throw "找不到构建产物：$builtApk" }
[IO.Directory]::CreateDirectory((Split-Path $OutputApk -Parent)) | Out-Null
if ([IO.Path]::GetFullPath($builtApk) -ne $OutputApk) {
    Copy-Item -LiteralPath $builtApk -Destination $OutputApk -Force
}
Get-FileHash -LiteralPath $OutputApk -Algorithm SHA256
