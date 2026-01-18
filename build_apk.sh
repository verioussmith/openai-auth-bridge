#!/bin/bash

# OpenAI Auth Bridge - APK Builder
# Run this on a machine with Android SDK installed

echo "==================================="
echo "OpenAI Auth Bridge - APK Builder"
echo "==================================="

# Check for Android SDK
if [ -z "$ANDROID_HOME" ]; then
    echo "ERROR: ANDROID_HOME not set"
    echo "Please install Android SDK and set ANDROID_HOME"
    echo "Example: export ANDROID_HOME=/opt/android-sdk"
    exit 1
fi

echo "Building APK..."
cd "$(dirname \"$0\")\"

# Build the release APK
./gradlew assembleRelease

if [ $? -eq 0 ]; then
    echo ""
    echo "==================================="
    echo "SUCCESS!"
    echo "APK location: app/build/outputs/apk/release/app-release.apk"
    echo "==================================="
    echo ""
    echo "Transfer this APK to your phone and install it."
else
    echo "Build failed!"
    exit 1
fi
