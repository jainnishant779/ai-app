# Downloads the sherpa-onnx Android AAR (not on Maven Central). Gitignored; ~50 MB.
$ver = "1.13.8"
$dst = Join-Path $PSScriptRoot "..\app\libs"
New-Item -ItemType Directory -Force $dst | Out-Null
$file = Join-Path $dst "sherpa-onnx-$ver.aar"
if (-not (Test-Path $file)) {
    Invoke-WebRequest "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$ver/sherpa-onnx-$ver.aar" -OutFile $file
}
Write-Output "$file ($([math]::Round((Get-Item $file).Length/1MB,1)) MB)"
