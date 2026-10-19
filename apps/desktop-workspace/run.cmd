@echo off
setlocal
python "%~dp0..\..\tooling\launch.py" %*
exit /b %errorlevel%
