param(
    [string]$RemoteDir = "files/models/stt/qwen3-hinglish",
    [string]$LocalDir = "tools/models/qwen3-hinglish"
)

$adb = if (Get-Command adb.exe -ErrorAction SilentlyContinue) { (Get-Command adb.exe).Source }
       else { "C:\Users\nishant\AppData\Local\Android\Sdk\platform-tools\adb.exe" }
$pkg = "com.nishu.app"

New-Item -ItemType Directory -Force $LocalDir | Out-Null
New-Item -ItemType Directory -Force "$LocalDir/tokenizer" | Out-Null

$files = @("conv_frontend.onnx", "encoder.int8.onnx", "decoder.int8.onnx", "tokenizer/vocab.json", "tokenizer/merges.txt", "tokenizer/tokenizer_config.json")

foreach ($f in $files) {
    $remoteFile = "$RemoteDir/$f"
    $targetFile = "$LocalDir/$f"
    $leaf = Split-Path $f -Leaf
    
    Write-Output "Copying $f to tmp..."
    & $adb shell "run-as $pkg cp $remoteFile /data/local/tmp/$leaf"
    & $adb shell "chmod 666 /data/local/tmp/$leaf"
    
    Write-Output "Pulling $leaf..."
    & $adb pull "/data/local/tmp/$leaf" $targetFile
    & $adb shell "rm /data/local/tmp/$leaf"
    
    $sz = if (Test-Path $targetFile) { [math]::Round((Get-Item $targetFile).Length / 1MB, 1) } else { 0 }
    Write-Output "Finished $($f): $sz MB"
}

Write-Output "Model successfully pulled to $LocalDir"
