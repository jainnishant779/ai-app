Remove-Item Env:\PYTHONHOME -ErrorAction SilentlyContinue
$env:JAVA_HOME = "D:\nishant\toolchain\jdk\jdk-17.0.20.1+1"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
& 'd:\nishant\ai-app\gradlew.bat' :app:installDebug --console=plain 2>&1
