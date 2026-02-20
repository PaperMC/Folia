@echo off
REM Workaround: paperweight runs "git config commit.gpgSign false" in temp dirs and gets exit 128 on some Windows setups.
REM Use a wrapper that skips that command (global is already false) so applyAllPatches can continue.
REM --no-configuration-cache ensures a fresh run so the wrapper git is used.
set "SCRIPTDIR=%~dp0"
set "PATH=%SCRIPTDIR%;%PATH%"
call "%SCRIPTDIR%gradlew.bat" applyAllPatches --no-configuration-cache %*
exit /b %errorlevel%
