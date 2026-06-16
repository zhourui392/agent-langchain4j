@echo off
setlocal

set "JAVA_HOME=C:\Program Files\Java\jdk-21.0.9"
set "PATH=%JAVA_HOME%\bin;%PATH%"

cd /d "%~dp0"

call mvn -B -ntp -q -pl agentkit-cli -am test-compile exec:java %*

endlocal
