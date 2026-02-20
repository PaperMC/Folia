@echo off
REM 绕过 PowerShell 执行策略，运行修复并 apply 补丁的脚本
set "SCRIPTDIR=%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPTDIR%fix-and-apply-patches.ps1" %*
exit /b %errorlevel%
