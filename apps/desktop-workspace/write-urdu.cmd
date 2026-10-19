@echo off
setlocal
rem --sample opens an unsaved copy of the disposable example; --open=path opens your file.
python "%~dp0..\..\tooling\launch.py" --app %*
exit /b %errorlevel%
