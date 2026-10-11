# Downloads sherpa-onnx-whisper-small INT8 model files from HuggingFace
param (
    [string]$DestinationDir = "tools/models/whisper-small"
)

$baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main"
$files = @(
    "small-tokens.txt",
    "small-encoder.int8.onnx",
    "small-decoder.int8.onnx"
)

if (-not (Test-Path $DestinationDir)) {
    New-Item -ItemType Directory -Force $DestinationDir | Out-Null
}

foreach ($f in $files) {
    $targetPath = Join-Path $DestinationDir $f
    if (-not (Test-Path $targetPath) -or (Get-Item $targetPath).Length -eq 0) {
        Write-Output "Downloading $f..."
        $url = "$baseUrl/$f"
        & curl.exe -L --fail --retry 3 -o $targetPath $url
    } else {
        Write-Output "Already exists: $f ($([math]::Round((Get-Item $targetPath).Length / 1MB, 1)) MB)"
    }
}

Write-Output "Whisper Small ready in: $DestinationDir"
