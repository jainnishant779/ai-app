# Pushes models into the debug app's private storage (run-as needs a debug build).
# Models survive app updates (adb install -r) but not uninstall. Uses ANDROID_SERIAL if set.
$adb = "C:\Users\ACER\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$pkg = "com.nishu.app"
$bin = "D:\nishant\llm_research\llama_bin"
$tool = "D:\nishant\toolchain"

function Push-One($local, $remote) {
    if (-not (Test-Path $local)) { Write-Output "SKIP (missing) $local"; return }
    $name = Split-Path $local -Leaf
    $have = & $adb shell "run-as $pkg stat -c %s files/models/$remote" 2>$null
    if ("$have".Trim() -eq "$((Get-Item $local).Length)") { Write-Output "present $remote"; return }
    # Not Split-Path: on Windows it returns backslashes, which Android would take as part of the folder name.
    $dir = $remote.Substring(0, $remote.LastIndexOf('/'))
    & $adb push $local "/data/local/tmp/$name" | Out-Null
    & $adb shell "run-as $pkg mkdir -p files/models/$dir"
    & $adb shell "run-as $pkg cp /data/local/tmp/$name files/models/$remote"
    & $adb shell "rm /data/local/tmp/$name"
    $size = & $adb shell "run-as $pkg stat -c %s files/models/$remote" 2>$null
    if ("$size".Trim() -eq "$((Get-Item $local).Length)") { Write-Output "pushed $name -> files/models/$remote" }
    else { Write-Output "FAILED to verify $remote (device size '$size')" }
}

# LLM
Push-One "$bin\qwen3-0.6b-Q4_K_M.gguf" "llm/model.gguf"

# STT: shared VAD, the Hinglish whisper (preferred), and the English tiny model (fallback)
$stt = "$bin\stt"
Push-One "$stt\silero_vad.onnx" "stt/silero_vad.onnx"
$swift = "$tool\convert\out\hinglish-swift"
Push-One "$swift\hinglish-swift-encoder.int8.onnx" "stt/hinglish-swift/hinglish-swift-encoder.int8.onnx"
Push-One "$swift\hinglish-swift-decoder.int8.onnx" "stt/hinglish-swift/hinglish-swift-decoder.int8.onnx"
Push-One "$swift\hinglish-swift-tokens.txt" "stt/hinglish-swift/hinglish-swift-tokens.txt"
Push-One "$stt\sherpa-onnx-whisper-tiny.en\tiny.en-encoder.int8.onnx" "stt/tiny.en-encoder.int8.onnx"
Push-One "$stt\sherpa-onnx-whisper-tiny.en\tiny.en-decoder.int8.onnx" "stt/tiny.en-decoder.int8.onnx"
Push-One "$stt\sherpa-onnx-whisper-tiny.en\tiny.en-tokens.txt" "stt/tiny.en-tokens.txt"

# Speaker identification
Push-One "$tool\diar\sherpa-onnx-pyannote-segmentation-3-0\model.int8.onnx" "stt/diar/pyannote-segmentation-3-0.int8.onnx"
Push-One "$tool\diar\3dspeaker_zh_en_adv.onnx" "stt/diar/3dspeaker-campplus-zh-en.onnx"
