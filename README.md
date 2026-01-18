# OpenAI Auth Bridge - Android App

A minimal Android app that bridges OAuth callbacks from your phone (residential IP) to your VPS.

## Why this exists

- Your VPS IP is blocked by Cloudflare (shows CAPTCHA)
- Your phone has a residential IP (not blocked)
- This app receives OAuth callbacks on your phone and forwards them to your VPS

## How to use

### Step 1: Build the APK

```bash
cd OpenAIAuthBridge
./gradlew assembleRelease
```

The APK will be at: `app/build/outputs/apk/release/app-release.apk`

### Step 2: Install on your phone

1. Transfer the APK to your phone
2. Enable "Install from unknown sources" in Android settings
3. Install the APK

### Step 3: Configure on your phone

1. Open the app
2. Enter your VPS URL (e.g., `https://your-vps.com`)
3. Tap "Start Server"

### Step 4: Modify the opencode plugin

On your VPS, modify the redirect URI:

```bash
sed -i 's|http://localhost:1455|http://YOUR_PHONE_IP:1455|g' \
  ~/.npm/_npx/*/node_modules/opencode-openai-codex-auth/dist/lib/auth/auth.js
```

Or wait for the updated version that accepts the phone's URL.

### Step 5: Complete OAuth

1. On your phone's browser, open the OAuth URL
2. Login with your ChatGPT credentials
3. The callback will be received by the app and forwarded to your VPS
4. Auth is complete!

## Uninstall

Simply uninstall the app like any other app. It makes no system changes.

## Safety

- Only uses INTERNET permission
- No root required
- No background services when closed
- Easy to uninstall
