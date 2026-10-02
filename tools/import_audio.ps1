# Runs any audio file through the app's pipeline (debug build only; the phone must be connected).
# Usage: .\tools\import_audio.ps1 -Path "C:\audio\meeting.mp3" -Title "Weekly meeting"
param([Parameter(Mandatory = $true)][string]$Path, [string]$Title = "")
$adb = "C:\Users\ACER\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$ffmpeg = (Resolve-Path (Join-Path $PSScriptRoot "..\.venv\Lib\site-packages\imageio_ffmpeg\binaries\ffmpeg-*.exe")).Path
$name = [IO.Path]::GetFileNameWithoutExtension($Path) -replace '[^A-Za-z0-9]+', '_'
$wav = Join-Path $env:TEMP "$name.wav"
if (-not $Title) { $Title = [IO.Path]::GetFileNameWithoutExtension($Path) }

# The app records 16 kHz mono PCM16, so that is the only format the pipeline reads.
& $ffmpeg -y -hide_banner -loglevel error -i $Path -ar 16000 -ac 1 -c:a pcm_s16le $wav
"{0:N1} min of audio -> {1:N1} MB WAV" -f (((Get-Item $wav).Length - 44) / 32000 / 60), ((Get-Item $wav).Length / 1MB)

& $adb push $wav "/data/local/tmp/$name.wav" | Out-Null
& $adb shell "run-as com.nishu.app mkdir -p files/imports"
& $adb shell "run-as com.nishu.app cp /data/local/tmp/$name.wav files/imports/$name.wav"
& $adb shell "rm /data/local/tmp/$name.wav"
Remove-Item $wav -ErrorAction SilentlyContinue
# Single-quoted for the device shell, otherwise brackets or spaces in the title break the command.
$safeTitle = $Title -replace "'", ""
& $adb shell "am broadcast -a com.nishu.app.DEBUG_IMPORT -n com.nishu.app/.debug.DebugReceiver --es file 'imports/$name.wav' --es title '$safeTitle'" | Select-Object -Last 1
