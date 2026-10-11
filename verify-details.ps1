param([string]$Device = '192.168.31.194:5555', [int]$Post = 6765046)
$ErrorActionPreference = 'Stop'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$output = Join-Path $PSScriptRoot 'dist\details-tests'
New-Item -ItemType Directory -Force -Path $output | Out-Null
function AdbCall([string[]]$Arguments) {
    $result = & $adb -s $Device @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw ($result -join "`n") }
    return $result
}
function Key([int[]]$Codes) {
    $null = AdbCall (@('shell','input','keyevent') + @($Codes | ForEach-Object { "$_" }))
    Start-Sleep -Milliseconds 300
}
function Snapshot([string]$Name) {
    $null = AdbCall @('shell','uiautomator','dump',"/sdcard/details-$Name.xml")
    $path = Join-Path $output "$Name.xml"
    $null = AdbCall @('pull',"/sdcard/details-$Name.xml",$path)
    [xml]$xml = Get-Content -LiteralPath $path -Raw -Encoding UTF8
    $focus = $xml.SelectSingleNode('//node[@focused="true"]')
    if (!$focus) { throw "Missing focus: $Name" }
    Write-Host "PASS $Name focus=$($focus.'resource-id')"
    return @{ Xml=$xml; Focus=$focus }
}
function Media($State) {
    if ($State.Focus.'resource-id' -notmatch ':id/(imageView|imgPreview|gifView|videoView|imgPlay)$') {
        throw 'Expected media focus'
    }
}
function Details($State) {
    $scroll = $State.Focus.SelectSingleNode('ancestor::node[contains(@resource-id,":id/scrollView")]')
    if (!$scroll) { throw 'Expected details focus' }
}

# Read-only navigation; never activate vote, favourite or download buttons.
$null = AdbCall @('shell','am','start','-S','-n','se.zepiwolf.tws.tv/se.zepiwolf.tws.PostActivity','--ei','id',"$Post",'--ez','full','false')
Start-Sleep -Seconds 5
Media (Snapshot 'initial-media')
Key @(19)
Media (Snapshot 'media-up-stays')
Key @(22)
$state = Snapshot 'enter-details'; Details $state
Key @(19)
$state = Snapshot 'details-top'; Details $state
Key @(21)
Media (Snapshot 'details-left-media')
Key @(20)
$state = Snapshot 'media-down-actions'
if ($state.Focus.'resource-id' -notmatch ':id/imgFavourite$') { throw 'Expected bottom bar' }
Key @(22)
$state = Snapshot 'actions-right'
if ($state.Focus.'resource-id' -notmatch ':id/imgComments$') { throw 'Expected next action' }
Key @(21,19)
Media (Snapshot 'actions-up-media')
Key @(22)
$foundTags = $false
$foundDescription = $false
$reachedActions = $false
for ($i = 0; $i -lt 60; $i++) {
    $state = Snapshot "details-$i"
    $id = $state.Focus.'resource-id'
    if ($id -match ':id/pLLDescription$' -and !$foundDescription) {
        $foundDescription = $true
        Key @(23)
        Details (Snapshot 'description-toggle')
        Key @(23)
    }
    if ($id -match ':id/txt_tag_item$' -and $state.Focus.SelectSingleNode('ancestor::node[contains(@resource-id,":id/lLTags")]')) {
        $foundTags = $true
    }
    if ($state.Focus.SelectSingleNode('ancestor::node[contains(@resource-id,":id/lLButtons")]')) {
        $reachedActions = $true
        break
    }
    Details $state
    Key @(1..5 | ForEach-Object { 20 })
}
if (!$foundTags) { throw 'Tag focus not reached' }
if (!$reachedActions) { throw 'Final detail did not enter bottom bar' }
Key @(19)
Media (Snapshot 'final-actions-up-media')
Write-Output "Details navigation passed; description present and toggled: $foundDescription"
