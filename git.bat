@echo off
REM Workaround for paperweight: "git config commit.gpgSign false" fails with exit 128 in some work dirs.
REM Skip that command (global gpgsign is already false); otherwise call real git.
set "ARGS=%*"
echo %ARGS% | findstr /i "gpgSign" >nul 2>&1
if %errorlevel% equ 0 (
  echo %ARGS% | findstr /i "config" >nul 2>&1
  if %errorlevel% equ 0 exit /b 0
)
set "GITEXE=%ProgramFiles%\Git\cmd\git.exe"
if not exist "%GITEXE%" set "GITEXE=%ProgramFiles(x86)%\Git\cmd\git.exe"
if not exist "%GITEXE%" set "GITEXE=git.exe"
"%GITEXE%" %*
exit /b %errorlevel%
