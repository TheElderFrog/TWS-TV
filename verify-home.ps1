param([string]$Device = '192.168.31.194:5555')
$ErrorActionPreference = 'Stop'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$output = Join-Path $PSScriptRoot 'dist\home-tests'
New-Item -ItemType Directory -Force -Path $output | Out-Null
function AdbCall([string[]]$Arguments) {
    $result = & $adb -s $Device @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw ($result -join "`n") }
    return $result
}
function Key([int[]]$Codes) {
    $null = AdbCall (@('shell','input','keyevent') + @($Codes | ForEach-Object { "$_" }))
    Start-Sleep -Milliseconds 500
}
function Snapshot([string]$Name, [string]$Focus) {
    $null = AdbCall @('shell','uiautomator','dump',"/sdcard/home-$Name.xml")
    $path = Join-Path $output "$Name.xml"
    $null = AdbCall @('pull',"/sdcard/home-$Name.xml",$path)
    [xml]$xml = Get-Content -LiteralPath $path -Raw -Encoding UTF8
    $focused = $xml.SelectSingleNode('//node[@focused="true"]')
    if (!$focused -or $focused.'resource-id' -notmatch ":id/$Focus`$") { throw "Unexpected focus: $Name $($focused.'resource-id')" }
    if ($xml.SelectSingleNode('//node[contains(@resource-id,":id/toolbar") or contains(@resource-id,":id/bottom_nav")]')) { throw "Legacy bars visible: $Name" }
    Write-Host "PASS $Name focus=$Focus"
    return $xml
}
function Counter($Xml) { return $Xml.SelectSingleNode('//node[contains(@resource-id,":id/txtPageNr")]').text }
function Shot([string]$Name) {
    $null = AdbCall @('shell','screencap','-p',"/sdcard/home-$Name.png")
    $null = AdbCall @('pull',"/sdcard/home-$Name.png",(Join-Path $output "$Name.png"))
}

# Read-only navigation. Use a safe-rated multi-page query before running.
$null = AdbCall @('shell','am','start','-S','-n','se.zepiwolf.tws.tv/se.zepiwolf.tws.MainActivity')
Start-Sleep -Seconds 5
$first = Snapshot 'grid' 'imgPreview'
$page = Counter $first
Shot 'grid'
Key @(19,21)
$null = Snapshot 'left-rail' 'home_search'
Shot 'left-rail'
Key @(22)
$null = Snapshot 'return-grid' 'imgPreview'
Key @(22,22,22,22,22)
$edge = Snapshot 'right-rail' 'home_previous_page'
if ((Counter $edge) -ne $page) { throw 'Direction key changed page' }
Shot 'right-rail'
Key @(20,23)
Start-Sleep -Seconds 3
$second = Snapshot 'next-page' 'home_next_page'
if ((Counter $second) -eq $page) { throw 'Next page button did not change page' }
Key @(19,23)
Start-Sleep -Seconds 2
$previous = Snapshot 'previous-page' 'home_previous_page'
if ((Counter $previous) -ne $page) { throw 'Previous page did not restore page' }
Key @(21)
$null = Snapshot 'page-return-grid' 'imgPreview'
Key @(1..20 | ForEach-Object { 20 })
$bottom = Snapshot 'bottom-limit' 'imgPreview'
$grid = $bottom.SelectSingleNode('//node[contains(@resource-id,":id/recyclerView") and @class="android.widget.GridView"]')
$focus = $bottom.SelectSingleNode('//node[@focused="true"]')
$outer = @([regex]::Matches($grid.bounds,'\d+') | ForEach-Object { [int]$_.Value })
$inner = @([regex]::Matches($focus.bounds,'\d+') | ForEach-Object { [int]$_.Value })
if ($inner[1] -lt $outer[1] + 4 -or $inner[3] -gt $outer[3] - 4) { throw 'Grid focus is clipped at bottom' }
Shot 'bottom-limit'
Key @(21,21,21,21,21,21,21)
$null = Snapshot 'left-from-bottom' 'home_search'
Key @(22)
$null = Snapshot 'remember-row' 'imgPreview'
Key @(1..20 | ForEach-Object { 19 })
$null = Snapshot 'top-limit' 'imgPreview'
Write-Host 'Home layout and remote checks passed.'
