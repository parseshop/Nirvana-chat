@echo off
title Nirvana SMS Builder
color 0b
echo =======================================================
echo              NIRVANA SMS BUILDER (CMD)
echo =======================================================
echo.
echo Starting Android compilation...
echo.

:: Check if Java is available in path
where java >nul 2>nul
if %errorlevel% neq 0 (
    echo [ERROR] Java SDK was not found in your PATH. 
    echo Please make sure JDK is installed and JAVA_HOME is configured.
    echo.
    pause
    exit /b 1
)

:: Run assembly using gradlew.bat
echo Running Gradle build process...
call gradlew.bat assembleDebug

if %ERRORLEVEL% EQU 0 (
    echo.
    echo =======================================================
    echo [SUCCESS] Android APK built successfully!
    echo.
    echo Your final APK is ready at:
    echo app\build\outputs\apk\debug\app-debug.apk
    echo =======================================================
) else (
    echo.
    echo =======================================================
    echo [ERROR] Gradle compilation failed.
    echo Please check the error messages above for more details.
    echo =======================================================
)
echo.
pause
