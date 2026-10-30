<div align="center">
  <h1>LeakResearch</h1>
  <p><b>Native Android incident-report composer</b></p>
  <p>Local-first • Telegram Bot API • Redaction-first sharing</p>
</div>

## What it does
LeakResearch turns unstructured incident notes into a readable Telegram HTML report. It previews the sanitized message before sending it to a configured private group and optional topic.

## Privacy and safety
- Sensitive-looking email addresses, phone numbers, long numeric identifiers and credential-like values are masked before preview and delivery.
- The app does not upload notes to an AI provider.
- Telegram delivery is the only network operation initiated by the app.
- Bot token and destination settings are stored with Android encrypted preferences.
- Use only with information you are authorized to handle. Review every preview before sending.

## Build
Open this repository in Android Studio or run:

`gradle :app:assembleDebug`

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Telegram setup
Create a bot with BotFather, add it to a private group with permission to post, then enter its token, group Chat ID and optional topic ID in Settings. Keep the token private.

## Compatibility
Android 8.0+ (API 26). Package: `com.leakresearch`.
