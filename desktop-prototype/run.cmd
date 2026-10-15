@echo off
setlocal
pushd "%~dp0.."
set "EDITOR_MVN=%~dp0..\research\tools\apache-maven-3.9.9\bin\mvn.cmd"
if exist "%EDITOR_MVN%" goto build
where mvn.cmd >nul 2>nul
if errorlevel 1 goto missing
set "EDITOR_MVN=mvn.cmd"
:build
call "%EDITOR_MVN%" -f "%~dp0pom.xml" -q -B -ntp compile dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/lib -Dmdep.prependGroupId=true
if errorlevel 1 goto failed
set "CLASSPATH=%~dp0target\classes;%~dp0target\lib\*"
if /I "%~1"=="--check" goto check
start "" javaw.exe editor.MainKt
if errorlevel 1 goto failed
goto done
:check
java.exe editor.MainKt --check
if errorlevel 1 goto failed
goto done
:missing
echo Maven 3.9+ is required. Add mvn.cmd to PATH, then try again.
:failed
popd
exit /b 1
:done
popd
exit /b 0
