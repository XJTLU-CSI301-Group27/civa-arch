@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-usb-device.ps1" %*
set "usb_exit=%errorlevel%"
if not "%usb_exit%"=="0" echo USB startup failed. Read the message above.
pause
exit /b %usb_exit%
