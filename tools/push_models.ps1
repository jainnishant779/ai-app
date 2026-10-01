# Pushes the LLM and STT models into the debug app's private storage (run-as needs a debug build).
# Models survive app updates (adb install -r) but not uninstall.
$adb = "C:\Users\ACER\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$pkg = "com.nishu.app"
$bin = "D:\nishant\llm_research\llama_bin"

function Push-One($local, $remote) {
    $name = Split-Path $local -Leaf
    & $adb push $local "/data/local/tmp/$name" | Out-Null
    & $adb shell "run-as $pkg mkdir -p files/models/llm files/models/stt"
    & $adb shell "run-as $pkg cp /data/local/tmp/$name files/models/$remote"
    & $adb shell "rm /data/local/tmp/$name"
    Write-Output "pushed $name -> files/models/$remote"
}

Push-One "$bin\qwen3-0.6b-Q4_K_M.gguf" "llm/model.gguf"
$stt = "$bin\stt"
Push-One "$stt\silero_vad.onnx" "stt/silero_vad.onnx"
Push-One "$stt\sherpa-onnx-whisper-tiny.en\tiny.en-encoder.int8.onnx" "stt/tiny.en-encoder.int8.onnx"
Push-One "$stt\sherpa-onnx-whisper-tiny.en\tiny.en-decoder.int8.onnx" "stt/tiny.en-decoder.int8.onnx"
Push-One "$stt\sherpa-onnx-whisper-tiny.en\tiny.en-tokens.txt" "stt/tiny.en-tokens.txt"
