param([Parameter(Mandatory=$true)][string]$Device)
$ErrorActionPreference = 'Stop'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$output = Join-Path $PSScriptRoot 'dist\search-tests'
New-Item -ItemType Directory -Path $output -Force | Out-Null
function AdbCall([string[]]$Arguments) {
    $result = & $adb -s $Device @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw ($result -join "`n") }
    return $result
}
function Key([int[]]$Codes) {
    $null = AdbCall (@('shell','input','keyevent') + @($Codes | ForEach-Object { "$_" }))
    Start-Sleep -Milliseconds 400
}
function Snapshot([string]$Name) {
    $remote = "/sdcard/search-$Name.xml"
    $result = AdbCall @('shell','uiautomator','dump',$remote)
    if ($result -match 'ERROR') { throw "Hierarchy unavailable: $Name" }
    $path = Join-Path $output "$Name.xml"
    $null = AdbCall @('pull',$remote,$path)
    $xml = [xml](Get-Content -LiteralPath $path -Raw -Encoding UTF8)
    Write-Host "PASS snapshot $Name"
    return $xml
}
function IsPage($Xml) { return $null -ne $Xml.SelectSingleNode('//node[@class="android.widget.EditText" and not(contains(@resource-id, ":id/search_src_text"))]') }
function CheckKeyboard([bool]$Expected) {
    $state = (AdbCall @('shell','dumpsys','input_method')) -join "`n"
    if (($state -match 'mInputShown=true') -ne $Expected) { throw "Keyboard expected $Expected" }
    Write-Host "PASS keyboard=$Expected"
}
function Shot([string]$Name) {
    $null = AdbCall @('shell','screencap','-p',"/sdcard/search-$Name.png")
    try { $null = AdbCall @('pull',"/sdcard/search-$Name.png",(Join-Path $output "$Name.png")) }
    catch {
        Start-Sleep -Seconds 1
        $null = AdbCall @('pull',"/sdcard/search-$Name.png",(Join-Path $output "$Name.png"))
    }
}
function SelectedTitle($Xml) {
    $list = $Xml.SelectSingleNode('//node[@class="android.widget.ListView"]')
    if (!$list -or $list.focused -ne 'true') { throw 'Suggestion list lacks focus' }
    $row = $list.SelectSingleNode('node[@selected="true"]')
    if (!$row) { throw 'No selected suggestion' }
    return $row.SelectSingleNode('.//node[@class="android.widget.TextView"]').text
}

# Only navigation and a safe-rated search; no account or content writes.
$null = AdbCall @('shell','am','start','-S','-n','se.zepiwolf.tws.tv/se.zepiwolf.tws.MainActivity')
Start-Sleep -Seconds 4
Key @(23,19)
$homeState = Snapshot 'home'
$original = $homeState.SelectSingleNode('//node[contains(@resource-id, ":id/search_src_text")]').text
Key @(23)
Start-Sleep -Seconds 2
CheckKeyboard $true
$page = Snapshot 'keyboard'
if (!(IsPage $page)) { throw 'Fullscreen search missing' }
Shot 'keyboard'
Key @(123)
if ($original.Length -gt 0) { Key @(1..$original.Length | ForEach-Object { 67 }) }
$null = AdbCall @('shell','input','text','rating:s%swol')
Start-Sleep -Seconds 2
Key @(4)
CheckKeyboard $false
$page = Snapshot 'keyboard-hidden'
if (!(IsPage $page)) { throw 'First Back closed search' }
Key @(20)
$page = Snapshot 'first-suggestion'
$firstRow = $page.SelectSingleNode('//node[@class="android.widget.ListView"]/node[1]')
$firstTexts = @($firstRow.SelectNodes('.//node[@class="android.widget.TextView"]'))
$first = $firstTexts[0].text
$expectedQuery = $first
if ($firstTexts.Count -gt 1) { $expectedQuery = $firstTexts[1].text.Trim() + ' ' + $first.Trim() }
if ((SelectedTitle $page) -ne $first) { throw 'First Down did not select first suggestion' }
Shot 'suggestions'
Key @(20)
$second = SelectedTitle (Snapshot 'second-suggestion')
if ($second -eq $first) { throw 'Down failed to advance selection' }
Key @(19)
if ((SelectedTitle (Snapshot 'first-again')) -ne $first) { throw 'Up failed to restore selection' }
Key @(1..12 | ForEach-Object { 20 })
$scrolled = Snapshot 'scrolled'
$list = $scrolled.SelectSingleNode('//node[@class="android.widget.ListView"]')
$row = $list.SelectSingleNode('node[@selected="true"]')
if (!$row) { throw 'Scrolled list lost selection' }
$outer = @([regex]::Matches($list.bounds,'\d+') | ForEach-Object { [int]$_.Value })
$inner = @([regex]::Matches($row.bounds,'\d+') | ForEach-Object { [int]$_.Value })
if ($inner[1] -lt $outer[1] + 4 -or $inner[3] -gt $outer[3] - 4) { throw 'Scrolled selection clipped' }
Shot 'scrolled'
Key @(1..12 | ForEach-Object { 19 })
if ((SelectedTitle (Snapshot 'scrolled-back')) -ne $first) { throw 'Scrolled Up did not restore first item' }
Key @(23)
Start-Sleep -Seconds 3
$result = Snapshot 'submitted'
if (IsPage $result) { throw 'Submission did not leave search' }
$query = $result.SelectSingleNode('//node[contains(@resource-id, ":id/search_src_text")]').text
if ($query -ne $expectedQuery) { throw "Wrong submitted query: $query" }
if ($result.SelectSingleNode('//node[@class="android.widget.ListView"]')) { throw 'Legacy suggestion popup returned' }
CheckKeyboard $false
Shot 'submitted'
Key @(19,23)
Start-Sleep -Seconds 2
CheckKeyboard $true
Key @(4,20,4)
$cancel = Snapshot 'cancelled'
if (IsPage $cancel) { throw 'Second Back did not return home' }
if ($cancel.SelectSingleNode('//node[contains(@resource-id, ":id/search_src_text")]').text -ne $expectedQuery) { throw 'Cancel changed original query' }
Key @(23)
Start-Sleep -Seconds 2
Key @(4,23)
Start-Sleep -Seconds 2
CheckKeyboard $true
Key @(4,4)
if (IsPage (Snapshot 'cancelled-from-input')) { throw 'Second Back from input failed' }
Write-Host 'Fullscreen search regression passed.'
