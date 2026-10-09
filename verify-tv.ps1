param([string]$Device = '192.168.31.194:5555')
$ErrorActionPreference = 'Stop'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$output = Join-Path $PSScriptRoot 'dist\viewer-tests'
New-Item -ItemType Directory -Path $output -Force | Out-Null
function AdbCall([string[]]$Arguments) {
    $result = & $adb -s $Device @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw ($result -join "`n") }
    return $result
}
function Key([int[]]$Codes) {
    $null = AdbCall (@('shell','input','keyevent') + @($Codes | ForEach-Object { "$_" }))
    Start-Sleep -Milliseconds 250
}
function Launch([int]$Post) {
    $null = AdbCall @('shell','am','start','-S','-n','se.zepiwolf.tws.tv/se.zepiwolf.tws.PostActivity','--ei','id',"$Post",'--ez','full','false')
    Start-Sleep -Seconds 3
}
function Snapshot([string]$Name) {
    $remote = "/sdcard/tv-$Name.xml"
    $result = AdbCall @('shell','uiautomator','dump',$remote)
    if ($result -match 'ERROR') { throw "Hierarchy unavailable: $Name" }
    $path = Join-Path $output "$Name.xml"
    $null = AdbCall @('pull',$remote,$path)
    $xml = [xml](Get-Content -LiteralPath $path -Raw -Encoding UTF8)
    $nodes = @($xml.SelectNodes('//node'))
    if ($nodes | Where-Object { $_.'resource-id' -match ':id/adContainer$' }) { throw "Empty banner visible: $Name" }
    $focused = @($nodes | Where-Object { $_.focused -eq 'true' })
    if ($focused.Count -ne 1) { throw "Expected one focus: $Name ($($focused.Count))" }
    Write-Host "PASS $Name focus=$($focused[0].'resource-id') $($focused[0].'content-desc')"
    return @{ Xml=$xml; Nodes=$nodes; Focus=$focused[0] }
}
function CheckFocus($State, [string]$Suffix) {
    if ($State.Focus.'resource-id' -notmatch ":id/$Suffix`$") { throw "Unexpected focus, expected $Suffix" }
}
function CheckFull($State, [bool]$Expected) {
    $exists = @($State.Nodes | Where-Object { $_.'resource-id' -match ':id/contentFrameFullscreen$' }).Count -gt 0
    if ($exists -ne $Expected) { throw "Fullscreen state incorrect" }
}
function Screenshot([string]$Name) {
    $remote = "/sdcard/tv-$Name.png"
    $null = AdbCall @('shell','screencap','-p',$remote)
    $null = AdbCall @('pull',$remote,(Join-Path $output "$Name.png"))
}

# Navigation only: no voting, favourites, downloads or other online writes.
Launch 6765046
$state = Snapshot 'static-detail'; CheckFocus $state 'imageView'; CheckFull $state $false
Key @(20)
$state = Snapshot 'actions'; CheckFocus $state 'imgFavourite'
Screenshot 'actions'
Key @(22)
$state = Snapshot 'action-next'; CheckFocus $state 'imgComments'
Key @(19,19)
$state = Snapshot 'details-first'
Key @(1..35 | ForEach-Object { 20 })
$state = Snapshot 'details-scroll'
if ($state.Focus.'resource-id' -notmatch ':id/txt_tag_item$') { throw 'Details navigation did not reach tags' }
Screenshot 'details-scroll'
Key @(21,23)
$state = Snapshot 'static-full'; CheckFull $state $true; CheckFocus $state 'imageView'
Key @(20)
$state = Snapshot 'static-controls'
if ($state.Focus.'content-desc' -ne '下一张') { throw 'Fullscreen controls did not receive focus on first press' }
Screenshot 'static-controls'
Key @(19,4)
$state = Snapshot 'static-exit'; CheckFull $state $false; CheckFocus $state 'imageView'

Launch 6765011
Key @(23)
Start-Sleep -Seconds 4
$state = Snapshot 'video-playing'; CheckFull $state $true; CheckFocus $state 'videoView'
Screenshot 'video-playing'
Key @(23)
$state = Snapshot 'video-paused'
if ($state.Focus.'content-desc' -notmatch '播放|Play') { throw 'Playback did not pause' }
Screenshot 'video-paused'
Key @(22)
$state = Snapshot 'video-seek-focus'
if ($state.Focus.'content-desc' -ne '快进') { throw 'Seek button focus incorrect' }
Key @(23)
$state = Snapshot 'video-seeked'
if ($state.Focus.'content-desc' -ne '快进') { throw 'Seek stole focus' }
Screenshot 'video-seeked'
Key @(21,23)
Start-Sleep -Seconds 2
$state = Snapshot 'video-resumed'
if ($state.Focus.'content-desc' -notmatch '暂停|Pause') { throw 'Playback did not resume' }
Key @(19,22,20,23)
$state = Snapshot 'video-media-seek-paused'
Screenshot 'video-media-seek-paused'
Key @(4)
$state = Snapshot 'video-exit'; CheckFull $state $false; CheckFocus $state 'videoView'

$null = AdbCall @('shell','am','start','-n','se.zepiwolf.tws.tv/se.zepiwolf.tws.MainActivity')
Start-Sleep -Seconds 3
$state = Snapshot 'home'; CheckFocus $state 'imgPreview'
Key @(21)
$state = Snapshot 'home-search'; CheckFocus $state 'home_search'
Key @(22)
$state = Snapshot 'home-return'; CheckFocus $state 'imgPreview'
Screenshot 'home'
Write-Output 'All TV viewer checks passed.'
