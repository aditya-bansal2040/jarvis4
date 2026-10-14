@echo off
title JARVIS Android - Package Export
color 0b

echo ===============================================================
echo     J.A.R.V.I.S. NATIVE ANDROID - 1-CLICK CLOUD EXPORT
echo ===============================================================
echo.
echo Packaging project files for GitHub Cloud Builder...
echo.

cd /d "%~dp0"
powershell -Command "Compress-Archive -Path '*' -DestinationPath '..\Jarvis-Android-Ready.zip' -Force"

echo.
echo [SUCCESS] Package created: D:\antigravity\Jarvis-Android-Ready.zip
echo.
echo Next Steps (Zero PC Load Cloud Build):
echo 1. Open https://github.com/new and create a new repository (e.g. 'jarvis-android').
echo 2. Upload the files or zip to your GitHub repository.
echo 3. Click the 'Actions' tab -> 'Build Jarvis Android APK' will automatically start!
echo 4. In 2 minutes, click 'Jarvis-Android-APK' to download your installable APK directly to your phone!
echo.
pause
