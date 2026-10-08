param([string]$JavaHome = $env:JAVA_HOME, [string]$Sdk = $env:ANDROID_HOME, [string]$Stage = (Join-Path $env:TEMP 'tws-tv-locale-test'))
$ErrorActionPreference = 'Stop'
$project = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$stage = $Stage
$bt = Join-Path $Sdk 'build-tools\37.0.0'
$android = Join-Path $Sdk 'platforms\android-35\android.jar'
$env:JAVA_HOME = $JavaHome
function Check { if ($LASTEXITCODE -ne 0) { throw "Test build failed: $LASTEXITCODE" } }
if (!$env:TWS_TV_KEYSTORE_PASSWORD) { throw 'Set TWS_TV_KEYSTORE_PASSWORD for the local signing key.' }
New-Item -ItemType Directory -Force -Path "$stage\classes", "$stage\dex", "$stage\assets" | Out-Null
Copy-Item -LiteralPath "$project\locales\strings.json" -Destination "$stage\assets\strings.json" -Force
Copy-Item -LiteralPath "$PSScriptRoot\AndroidManifest.xml" -Destination "$stage\AndroidManifest.xml" -Force
& "$JavaHome\bin\javac.exe" -encoding UTF-8 --release 8 -classpath $android -d "$stage\classes" "$PSScriptRoot\LocaleTest.java"
Check
& "$bt\d8.bat" --min-api 24 --lib $android --output "$stage\dex" "$stage\classes\se\zepiwolf\tws\tv\localetest\LocaleTest.class"
Check
& "$bt\aapt2.exe" link -I $android --manifest "$stage\AndroidManifest.xml" -A "$stage\assets" -o "$stage\unsigned.apk"
Check
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open("$stage\unsigned.apk", 'Update')
try { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$stage\dex\classes.dex", 'classes.dex') | Out-Null }
finally { $zip.Dispose() }
& "$bt\zipalign.exe" -f 4 "$stage\unsigned.apk" "$stage\aligned.apk"
Check
& "$bt\apksigner.bat" sign --ks "$project\tv-signing.keystore" --ks-pass env:TWS_TV_KEYSTORE_PASSWORD --key-pass env:TWS_TV_KEYSTORE_PASSWORD --ks-key-alias twstv --out "$stage\locale-test.apk" "$stage\aligned.apk"
Check
