# ============================================================================
#  XingtuVip —— 手工构建脚本（不依赖 Gradle）
#
#  流水线：javac → d8 → aapt2 compile/link → 组装 → zipalign → apksigner
#
#  用法：
#      powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
#
#  可选环境变量（不设则自动探测）：
#      JAVA_HOME           JDK 17+ 根目录（必须是完整 JDK，不能是 JRE）
#      ANDROID_HOME        Android SDK 根目录
#      ANDROID_SDK_ROOT    同上（备选）
# ============================================================================
$ErrorActionPreference = 'Stop'

$Root       = $PSScriptRoot
$SrcRoot    = Join-Path $Root 'app\src\main'
$OutRoot    = Join-Path $Root 'build'
$LibDir     = Join-Path $Root 'lib'
$ApiAar     = Join-Path $LibDir 'api-101.0.1.aar'
$ApiAarUrl  = 'https://repo1.maven.org/maven2/io/github/libxposed/api/101.0.1/api-101.0.1.aar'

function Write-Step([string]$msg) { Write-Host $msg -ForegroundColor Cyan }
function Fail([string]$msg) { throw $msg }

# ---------------------------------------------------------------------------
# 1. 定位 JDK 17+
# ---------------------------------------------------------------------------
function Resolve-JavaHome {
    $cands = New-Object System.Collections.Generic.List[string]
    if ($env:JAVA_HOME) { $cands.Add($env:JAVA_HOME) }
    # Android Studio 自带 JBR / 常见安装位置
    foreach ($p in @(
            "$env:LOCALAPPDATA\Programs\Android Studio\jbr",
            "$env:ProgramFiles\Android\Android Studio\jbr",
            "$env:ProgramFiles\Java",
            "$env:ProgramFiles\Eclipse Adoptium",
            "$env:ProgramFiles\Microsoft",
            "$env:ProgramFiles\Amazon Corretto",
            'C:\Program Files\Zulu')) {
        if (Test-Path $p) {
            $cands.Add($p)
            Get-ChildItem $p -Directory -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -match 'jdk|jbr|java|corretto|temurin|zulu' } |
                ForEach-Object { $cands.Add($_.FullName) }
        }
    }
    foreach ($c in $cands) {
        $javac = Join-Path $c 'bin\javac.exe'
        if (-not (Test-Path $javac)) { continue }
        $verLine = & $javac -version 2>&1 | Select-Object -First 1
        if ($verLine -match 'javac\s+(\d+)') {
            $major = [int]$Matches[1]
            if ($major -ge 17) {
                Write-Host "  JDK: $c  ($verLine)"
                return $c
            }
        }
    }
    Fail "未找到 JDK 17+。请设置 JAVA_HOME 指向完整 JDK（不是 JRE）。"
}

# ---------------------------------------------------------------------------
# 2. 定位 Android SDK 与 build-tools
# ---------------------------------------------------------------------------
function Resolve-Sdk {
    $cands = New-Object System.Collections.Generic.List[string]
    if ($env:ANDROID_HOME) { $cands.Add($env:ANDROID_HOME) }
    if ($env:ANDROID_SDK_ROOT) { $cands.Add($env:ANDROID_SDK_ROOT) }
    $lp = Join-Path $Root 'local.properties'
    if (Test-Path $lp) {
        $line = Get-Content $lp | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
        if ($line) {
            $v = ($line -split '=', 2)[1].Trim() -replace '\\(.)', '$1' -replace '\\\\', '\'
            $cands.Add($v)
        }
    }
    foreach ($p in @("$env:LOCALAPPDATA\Android\Sdk", "$env:ProgramFiles\Android\Sdk", 'D:\Android\Sdk', 'E:\Android\Sdk')) {
        $cands.Add($p)
    }
    foreach ($c in $cands) {
        if ($c -and (Test-Path (Join-Path $c 'build-tools'))) {
            Write-Host "  SDK: $c"
            return $c
        }
    }
    Fail "未找到 Android SDK。请设置 ANDROID_HOME，或在 local.properties 写 sdk.dir=..."
}

function Resolve-BuildTools([string]$Sdk) {
    $dirs = Get-ChildItem (Join-Path $Sdk 'build-tools') -Directory |
        Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
        Sort-Object { [version]$_.Name } -Descending
    foreach ($d in $dirs) {
        if ((Test-Path (Join-Path $d.FullName 'aapt2.exe')) -and (Test-Path (Join-Path $d.FullName 'lib\d8.jar'))) {
            Write-Host "  build-tools: $($d.Name)"
            return $d.FullName
        }
    }
    Fail "build-tools 目录下没有可用的 aapt2 + d8.jar，请用 sdkmanager 安装 build-tools。"
}

function Resolve-AndroidJar([string]$Sdk) {
    $dirs = Get-ChildItem (Join-Path $Sdk 'platforms') -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^android-(\d+)$' } |
        Sort-Object { [int]($_.Name -replace 'android-', '') } -Descending
    foreach ($d in $dirs) {
        $jar = Join-Path $d.FullName 'android.jar'
        if (Test-Path $jar) {
            Write-Host "  platform: $($d.Name)"
            return $jar
        }
    }
    Fail "platforms 下没有 android.jar，请用 sdkmanager 安装任一 platform。"
}

# ---------------------------------------------------------------------------
# 3. 准备 libxposed API 101 的 classes.jar
# ---------------------------------------------------------------------------
function Resolve-ApiJar {
    $local = Join-Path $LibDir 'api-101.0.1-classes.jar'
    if (Test-Path $local) {
        Write-Host "  libxposed api: 本地缓存"
        return $local
    }
    if (-not (Test-Path $ApiAar)) {
        Write-Host "  libxposed api: 未找到 aar，尝试下载 ..."
        New-Item -ItemType Directory -Path $LibDir -Force | Out-Null
        try {
            [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
            Invoke-WebRequest $ApiAarUrl -OutFile $ApiAar -UseBasicParsing -TimeoutSec 120
        } catch {
            Fail @"
下载 libxposed api aar 失败。请手动执行：
  curl -L $ApiAarUrl -o lib\api-101.0.1.aar
或改用 Gradle 构建（./gradlew :app:assembleRelease）自动解析依赖。
"@
        }
    }
    Write-Host "  libxposed api: 从 aar 提取 classes.jar"
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($ApiAar)
    try {
        $entry = $zip.Entries | Where-Object { $_.FullName -eq 'classes.jar' } | Select-Object -First 1
        if (-not $entry) { Fail "api aar 内没有 classes.jar" }
        [void][IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $local, $true)
    } finally { $zip.Dispose() }
    return $local
}

# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------
Write-Step "[0/6] 探测构建环境"
$JavaHome = Resolve-JavaHome
$env:JAVA_HOME = $JavaHome
$env:PATH = "$JavaHome\bin;$env:PATH"

$Sdk        = Resolve-Sdk
$BuildTools = Resolve-BuildTools $Sdk
$AndroidJar = Resolve-AndroidJar $Sdk
$ApiJar     = Resolve-ApiJar

$java    = Join-Path $JavaHome 'bin\java.exe'
$javac   = Join-Path $JavaHome 'bin\javac.exe'
$keytool = Join-Path $JavaHome 'bin\keytool.exe'
$aapt2   = Join-Path $BuildTools 'aapt2.exe'
$d8Jar   = Join-Path $BuildTools 'lib\d8.jar'
$apkSign = Join-Path $BuildTools 'lib\apksigner.jar'
$zipalign = Join-Path $BuildTools 'zipalign.exe'

foreach ($p in @($java, $javac, $keytool, $aapt2, $d8Jar, $apkSign, $zipalign)) {
    if (-not (Test-Path $p)) { Fail "缺少构件: $p" }
}

$classes   = Join-Path $OutRoot 'classes'
$dexOut    = Join-Path $OutRoot 'dex'
$resComp   = Join-Path $OutRoot 'res'
$baseApk   = Join-Path $OutRoot 'base.apk'
$unaligned = Join-Path $OutRoot 'unaligned.apk'
$aligned   = Join-Path $OutRoot 'aligned.apk'
$unsigned  = Join-Path $OutRoot 'unsigned.apk'
$finalApk  = Join-Path $OutRoot 'XingTuVip-1.0.apk'
$keystore  = Join-Path $Root 'signing\module.keystore'

foreach ($d in @($classes, $dexOut, $resComp)) {
    if (Test-Path $d) { Remove-Item $d -Recurse -Force }
    New-Item -ItemType Directory -Path $d -Force | Out-Null
}
New-Item -ItemType Directory -Path $OutRoot -Force | Out-Null

# 1) javac
Write-Step "[1/6] javac"
$sources = Get-ChildItem (Join-Path $SrcRoot 'java') -Recurse -Filter *.java |
    ForEach-Object { $_.FullName }
& $javac -encoding UTF-8 -source 17 -target 17 -nowarn `
    -classpath "$AndroidJar;$ApiJar" -d $classes $sources
if ($LASTEXITCODE -ne 0) { Fail "javac 失败" }

# 2) d8
Write-Step "[2/6] d8"
$classFiles = Get-ChildItem $classes -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& $java -Xmx1g -cp $d8Jar com.android.tools.r8.D8 --release --min-api 26 `
    --lib $AndroidJar --classpath $ApiJar --output $dexOut $classFiles
if ($LASTEXITCODE -ne 0) { Fail "d8 失败" }

# 3) aapt2
Write-Step "[3/6] aapt2"
& $aapt2 compile --dir (Join-Path $SrcRoot 'res') -o $resComp
if ($LASTEXITCODE -ne 0) { Fail "aapt2 compile 失败" }
$flat = Get-ChildItem $resComp -Recurse -Filter *.flat | ForEach-Object { $_.FullName }
& $aapt2 link -o $baseApk -I $AndroidJar `
    --manifest (Join-Path $SrcRoot 'AndroidManifest.xml') `
    --min-sdk-version 26 --target-sdk-version 35 `
    $flat
if ($LASTEXITCODE -ne 0) { Fail "aapt2 link 失败" }

# 4) 组装
Write-Step "[4/6] 组装 APK"
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
Copy-Item $baseApk $unaligned -Force
$zip = [IO.Compression.ZipFile]::Open($unaligned, 'Update')
try {
    $dex = Join-Path $dexOut 'classes.dex'
    if (Test-Path $dex) {
        $e = $zip.GetEntry('classes.dex'); if ($e) { $e.Delete() }
        [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $zip, $dex, 'classes.dex', [IO.Compression.CompressionLevel]::Optimal)
        Write-Host "  + classes.dex"
    }
    $metaRoot = Join-Path $SrcRoot 'resources\META-INF\xposed'
    foreach ($f in Get-ChildItem $metaRoot -File) {
        $name = "META-INF/xposed/$($f.Name)"
        $e = $zip.GetEntry($name); if ($e) { $e.Delete() }
        [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $zip, $f.FullName, $name, [IO.Compression.CompressionLevel]::Optimal)
        Write-Host "  + $name"
    }
} finally { $zip.Dispose() }

# 5) zipalign
Write-Step "[5/6] zipalign"
& $zipalign -f -p 4 $unaligned $aligned
if ($LASTEXITCODE -ne 0) { Fail "zipalign 失败" }

# 6) 签名
Write-Step "[6/6] 签名"
New-Item -ItemType Directory -Path (Split-Path $keystore -Parent) -Force | Out-Null
if (-not (Test-Path $keystore)) {
    Write-Host "  生成新 keystore（口令 android）"
    $savedEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & $keytool -genkeypair -keystore $keystore -storepass android -keypass android `
        -alias module -keyalg RSA -keysize 2048 -validity 10950 `
        -dname "CN=XingtuVip, OU=RE, O=local, L=local, ST=local, C=CN" 2>&1 | Out-Null
    $rc = $LASTEXITCODE
    $ErrorActionPreference = $savedEap
    if ($rc -ne 0 -and -not (Test-Path $keystore)) { Fail "keytool 失败" }
}
Copy-Item $aligned $unsigned -Force
& $java -jar $apkSign sign --ks $keystore --ks-pass pass:android --key-pass pass:android `
    --out $finalApk $unsigned
if ($LASTEXITCODE -ne 0) { Fail "apksigner 失败" }

& $java -jar $apkSign verify --print-certs $finalApk | Select-Object -First 4

Write-Host ""
Write-Host "构建完成: $finalApk" -ForegroundColor Green
Write-Host ("大小: {0:N1} KB" -f ((Get-Item $finalApk).Length / 1KB))
