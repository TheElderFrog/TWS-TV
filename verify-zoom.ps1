param([string]$Device = '192.168.31.194:5555', [int]$Post = 6765046)
$ErrorActionPreference = 'Stop'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$output = Join-Path $PSScriptRoot 'dist\zoom-tests'
New-Item -ItemType Directory -Force -Path $output | Out-Null
function AdbCall([string[]]$Arguments) {
    $result = & $adb -s $Device @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw ($result -join "`n") }
    return $result
}
function Key([int[]]$Codes) {
    foreach ($code in $Codes) {
        $null = AdbCall @('shell','input','keyevent',"$code")
        Start-Sleep -Milliseconds 350
    }
}
function Shot([string]$Name) {
    Start-Sleep -Seconds 1
    $null = AdbCall @('shell','screencap','-p',"/sdcard/zoom-$Name.png")
    $path = Join-Path $output "$Name.png"
    $null = AdbCall @('pull',"/sdcard/zoom-$Name.png",$path)
    return (Get-FileHash -LiteralPath $path).Hash
}
function State([string]$Name) {
    $null = AdbCall @('shell','uiautomator','dump',"/sdcard/zoom-$Name.xml")
    $path = Join-Path $output "$Name.xml"
    $null = AdbCall @('pull',"/sdcard/zoom-$Name.xml",$path)
    return [xml](Get-Content -LiteralPath $path -Raw -Encoding UTF8)
}
# Static safe-rated image; no account writes or content actions.
$null = AdbCall @('shell','am','start','-S','-n','se.zepiwolf.tws.tv/se.zepiwolf.tws.PostActivity','--ei','id',"$Post",'--ez','full','false')
Start-Sleep -Seconds 8
$null = Shot 'detail'
Key @(23)
Start-Sleep -Seconds 2
$fit = Shot 'fit'
Key @(23)
$zoom = Shot '2x'
if ($zoom -eq $fit) { throw 'Zoom did not change pixels' }
Key @(20,22)
$pan = Shot 'pan'
if ($pan -eq $zoom) { throw 'Pan did not change pixels' }
$state = State 'pan'
if (!$state.SelectSingleNode('//node[contains(@resource-id,":id/contentFrameFullscreen")]')) { throw 'Pan left fullscreen' }
if ($state.SelectSingleNode('//node[@focused="true"]').'resource-id' -notmatch ':id/imageView$') { throw 'Pan moved focus to controls' }
Key @(23,23,23)
if ((Shot 'cycle-fit') -ne $fit) { throw 'Cycle did not restore original fit pixels' }
Key @(23,20,4)
if ((Shot 'back-fit') -ne $fit) { throw 'Back did not restore original fit pixels' }
$state = State 'back-fit'
if (!$state.SelectSingleNode('//node[contains(@resource-id,":id/contentFrameFullscreen")]')) { throw 'First Back exited fullscreen instead of zoom' }
Key @(20)
$state = State 'controls'
if ($state.SelectSingleNode('//node[@focused="true"]').'resource-id' -match ':id/imageView$') { throw 'Fit Down did not enter controls' }
Key @(19,4)
$state = State 'exit'
if ($state.SelectSingleNode('//node[contains(@resource-id,":id/contentFrameFullscreen")]')) { throw 'Fit Back did not exit fullscreen' }
Write-Output 'Zoom cycle, pan, fit restoration and navigation checks passed.'
