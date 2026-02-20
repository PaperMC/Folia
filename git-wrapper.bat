@echo off
REM Workaround for paperweight: git config commit.gpgSign false fails with exit 128 in some work dirs.
REM If this is that command, skip it (global gpgsign is already false) and exit 0.
set "ARGS=%*"
echo %ARGS% | findstr /C:"config" | findstr /C:"gpgSign" | findstr /C:"false" >nul
if %errorlevel% equ 0 exit /b 0
REM Call real git (avoid recursion: remove this script's dir from PATH)
set "PATH=%PATH:%~dp0=%;%PATH:%~dp0;=%;"
"%ProgramFiles%\Git\cmd\git.exe" %*
exit /b %errorlevel%
