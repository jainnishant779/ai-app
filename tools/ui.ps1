# UI automation helpers for the connected device. Dot-source: . .\tools\ui.ps1
$script:adb = "C:\Users\ACER\AppData\Local\Android\Sdk\platform-tools\adb.exe"

function Get-UiNodes {
    & $script:adb shell uiautomator dump /sdcard/ui.xml | Out-Null
    $tmp = Join-Path $env:TEMP "nishu_ui.xml"
    & $script:adb pull /sdcard/ui.xml $tmp | Out-Null
    [xml]$x = Get-Content $tmp -Raw -Encoding UTF8
    $x.SelectNodes("//node")
}

function Find-Ui([string]$Text) {
    Get-UiNodes | Where-Object { $_.text -like "*$Text*" -or $_.'content-desc' -like "*$Text*" } | Select-Object -First 1
}

function Tap-Ui([string]$Text, [int]$WaitMs = 1200) {
    $n = Find-Ui $Text
    if (-not $n) { Write-Output "NOT FOUND: $Text"; return $false }
    if ($n.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        $x = ([int]$Matches[1] + [int]$Matches[3]) / 2; $y = ([int]$Matches[2] + [int]$Matches[4]) / 2
        & $script:adb shell input tap $x $y
        Start-Sleep -Milliseconds $WaitMs
        return $true
    }
    $false
}

function Show-Ui { Get-UiNodes | Where-Object { $_.text -or $_.'content-desc' } | ForEach-Object { "{0} | {1} | {2}" -f $_.text, $_.'content-desc', $_.bounds } }
