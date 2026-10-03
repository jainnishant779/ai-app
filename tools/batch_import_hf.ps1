param([int[]]$Ids = @(2, 6, 9, 28, 52))

$adb = "C:\Users\ACER\AppData\Local\Android\Sdk\platform-tools\adb.exe"

foreach ($id in $Ids) {
    $wav = "tools/hf_samples/sample_$id.wav"
    if (-not (Test-Path $wav)) {
        Write-Warning "File not found: $wav"
        continue
    }
    Write-Host "Importing Sample $id to phone..."
    & $adb push $wav "/data/local/tmp/sample_$id.wav" | Out-Null
    & $adb shell "run-as com.nishu.app mkdir -p files/imports"
    & $adb shell "run-as com.nishu.app cp /data/local/tmp/sample_$id.wav files/imports/sample_$id.wav"
    & $adb shell "rm /data/local/tmp/sample_$id.wav"
    & $adb shell "am broadcast -a com.nishu.app.DEBUG_IMPORT -n com.nishu.app/.debug.DebugReceiver --es file 'imports/sample_$id.wav' --es title 'HF Sample $id'" | Out-Null
    Start-Sleep -Seconds 1
}
Write-Host "All samples queued on phone."
