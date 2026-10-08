param(
    [string]$Stage = (Join-Path $env:TEMP 'tws-tv-build'),
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Sdk = $env:ANDROID_HOME,
    [string]$Python = 'python',
    [string]$BuildTools = '37.0.0',
    [switch]$SkipDecode
)
$ErrorActionPreference = 'Stop'
$project = $PSScriptRoot
if (!$JavaHome) { throw 'Set JAVA_HOME or supply -JavaHome.' }
if (!$Sdk) { $Sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (!$env:TWS_TV_KEYSTORE_PASSWORD) { throw 'Set TWS_TV_KEYSTORE_PASSWORD to your own local signing password.' }
$java = Join-Path $JavaHome 'bin\java.exe'
$bt = Join-Path $Sdk "build-tools\$BuildTools"
$android = Join-Path $Sdk 'platforms\android-35\android.jar'
$libraries = @((Join-Path $project 'tools\zxing-core-3.5.3.jar'), (Join-Path $project 'tools\jsoup-1.18.3.jar'))
foreach ($file in @($java, $android, (Join-Path $bt 'd8.bat'), (Join-Path $project 'original.apk'), (Join-Path $project 'tools\apktool.jar'), (Join-Path $project 'tools\jsencrypt-3.3.2.min.js')) + $libraries) {
    if (!(Test-Path -LiteralPath $file)) { throw "Missing required file: $file" }
}
function Check { if ($LASTEXITCODE -ne 0) { throw "Build failed with exit code $LASTEXITCODE" } }
New-Item -ItemType Directory -Path $Stage -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $Stage 'temp') -Force | Out-Null
$env:JAVA_TOOL_OPTIONS = '-Xmx384m -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -Djava.io.tmpdir=' + (Join-Path $Stage 'temp')
Copy-Item -LiteralPath (Join-Path $project 'original.apk') -Destination (Join-Path $Stage 'original.apk') -Force
if (!$SkipDecode) {
    & $java -Xmx384m -jar (Join-Path $project 'tools\apktool.jar') d (Join-Path $Stage 'original.apk') -o (Join-Path $Stage 'decoded') -f
    Check
    & $Python (Join-Path $project 'patch.py') (Join-Path $Stage 'decoded')
    Check
    & $Python (Join-Path $project 'make_banner.py') (Join-Path $Stage 'decoded\res\drawable\tv_banner.png')
    Check
}
New-Item -ItemType Directory -Path (Join-Path $Stage 'classes'), (Join-Path $Stage 'dex') -Force | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $project 'src') -Recurse -Filter '*.java' | ForEach-Object FullName)
& (Join-Path $JavaHome 'bin\javac.exe') -encoding UTF-8 --release 8 -classpath ((@($android) + $libraries) -join ';') -d (Join-Path $Stage 'classes') @sources
Check
$classes = @(Get-ChildItem -LiteralPath (Join-Path $Stage 'classes') -Recurse -Filter '*.class' | ForEach-Object FullName)
& (Join-Path $bt 'd8.bat') --min-api 24 --lib $android --output (Join-Path $Stage 'dex') @classes @libraries
Check
& $java -Xmx384m -jar (Join-Path $project 'tools\apktool.jar') b (Join-Path $Stage 'decoded') -o (Join-Path $Stage 'unsigned.apk')
Check
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open((Join-Path $Stage 'unsigned.apk'), 'Update')
try {
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Join-Path $Stage 'dex\classes.dex'), 'classes3.dex') | Out-Null
} finally { $zip.Dispose() }
$keystore = Join-Path $project 'tv-signing.keystore'
if (!(Test-Path -LiteralPath $keystore)) {
    & (Join-Path $JavaHome 'bin\keytool.exe') -genkeypair -keystore $keystore -storepass:env TWS_TV_KEYSTORE_PASSWORD -keypass:env TWS_TV_KEYSTORE_PASSWORD -alias twstv -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=TWS Local TV Adaptation'
    Check
}
& (Join-Path $bt 'zipalign.exe') -f -p 4 (Join-Path $Stage 'unsigned.apk') (Join-Path $Stage 'aligned.apk')
Check
$apk = Join-Path $Stage 'TWS-TV.apk'
& (Join-Path $bt 'apksigner.bat') sign --ks $keystore --ks-pass env:TWS_TV_KEYSTORE_PASSWORD --key-pass env:TWS_TV_KEYSTORE_PASSWORD --ks-key-alias twstv --out $apk (Join-Path $Stage 'aligned.apk')
Check
& (Join-Path $bt 'apksigner.bat') verify --verbose $apk
Check
New-Item -ItemType Directory -Path (Join-Path $project 'dist') -Force | Out-Null
Copy-Item -LiteralPath $apk -Destination (Join-Path $project 'dist\TWS-TV.apk') -Force
Get-FileHash -LiteralPath (Join-Path $project 'dist\TWS-TV.apk') -Algorithm SHA256 | Format-List
