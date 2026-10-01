param([Parameter(Mandatory = $true)][string]$Name)
# Captures the connected device/emulator screen to docs/design/shots/<Name>.png
$adb = "C:\Users\ACER\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$out = Join-Path $PSScriptRoot "..\docs\design\shots"
New-Item -ItemType Directory -Force $out | Out-Null
& $adb shell screencap -p /sdcard/nishu_shot.png
& $adb pull /sdcard/nishu_shot.png (Join-Path $out "$Name.png") | Out-Null
& $adb shell rm /sdcard/nishu_shot.png
