# Installs the app + test APK without uninstalling (so pushed models survive), then runs instrumented tests.
# Usage: .\tools\run_device_tests.ps1 [-Filter com.nishu.app.llm.PromptBytesTest]
param([string]$Filter = "")
$ErrorActionPreference = "Continue"
$env:JAVA_HOME = "D:\nishant\toolchain\jdk\jdk-17.0.20.1+1"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$adb = "C:\Users\ACER\AppData\Local\Android\Sdk\platform-tools\adb.exe"
Set-Location (Join-Path $PSScriptRoot "..")

.\gradlew.bat :app:installDebug :app:installDebugAndroidTest --console=plain 2>&1 | Select-String "BUILD|FAILED|error:|^e: "
$have = & $adb shell "run-as com.nishu.app ls files/models/llm/model.gguf" 2>&1
if ("$have" -notmatch "model.gguf" -or "$have" -match "No such") { & "$PSScriptRoot\push_models.ps1" }

$args = @("shell", "am", "instrument", "-w", "-r")
if ($Filter) { $args += @("-e", "class", $Filter) }
$args += "com.nishu.app.test/androidx.test.runner.AndroidJUnitRunner"
& $adb @args 2>&1 | Select-String "INSTRUMENTATION_STATUS: (test|class|stack|numtests)=|INSTRUMENTATION_STATUS_CODE|OK \(|FAILURES|Tests run|NishuTest|Error|Exception" | Select-Object -First 120
