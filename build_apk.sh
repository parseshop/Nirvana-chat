#!/bin/bash

# Nirvana SMS Builder for Linux/Mac
echo -e "\e[1;36m======================================================="
echo "              NIRVANA SMS BUILDER (Terminal)"
echo -e "=======================================================\e[0m"
echo ""
echo "Starting Android compilation..."
echo ""

# Make gradle wrapper executable
chmod +x gradlew

# Run gradle build
./gradlew assembleDebug

if [ $? -eq 0 ]; then
    echo ""
    echo -e "\e[1;32m======================================================="
    echo "[SUCCESS] Android APK built successfully!"
    echo ""
    echo "Your final APK is ready at:"
    echo "app/build/outputs/apk/debug/app-debug.apk"
    echo -e "=======================================================\e[0m"
else
    echo ""
    echo -e "\e[1;31m======================================================="
    echo "[ERROR] Gradle compilation failed."
    echo "Please check the error messages above for details."
    echo -e "=======================================================\e[0m"
fi
echo ""
