Remove-Item Env:\PYTHONHOME -ErrorAction SilentlyContinue
$env:JAVA_HOME = 'D:\nishant\toolchain\jdk\jdk-17.0.20.1+1'
& 'd:\nishant\ai-app\gradlew.bat' :app:compileDebugKotlin 2>&1 | Select-Object -Last 50
