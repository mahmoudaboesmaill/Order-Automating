# Order Automating

Order Automating automates pharmacy purchase-invoice entry into the legacy
E-PLUS desktop application.

The workflow is:

1. The Android app captures one or more invoice pages.
2. The local Python server sends them to the configured OCR provider.
3. The user reviews item mapping, quantities, prices, and expiry dates.
4. `server.py` writes the reviewed invoice to runtime TSV files.
5. `OrderRobot.ahk` enters the data into E-PLUS.

The robot never saves the E-PLUS invoice automatically. Final review and save
remain manual safety steps.

## Repository layout

- `app/`: Android application written in Kotlin and Jetpack Compose.
- `server.py`: local Flask OCR and automation server.
- `OrderRobot.ahk`: AutoHotkey v2 automation for E-PLUS.
- `test_server_*.py`: focused server/session tests.
- `requirements.txt`: Python server dependencies.
- `server.env.example`: safe environment-variable examples.

## Requirements

- Android Studio with JDK 11 support.
- Python 3.8 or newer.
- AutoHotkey v2 on the E-PLUS Windows machine.
- Access to Gemini or Mistral OCR through server-side environment variables.

API keys belong on the server only. Do not add keys to Android source,
`local.properties`, commits, screenshots, or APK resources.

## Server setup

Create a virtual environment and install the dependencies:

```powershell
py.exe -m venv .venv
.\.venv\Scripts\Activate.ps1
py.exe -m pip install -r requirements.txt
```

Configure the required environment variables using `server.env.example` as a
reference. At minimum, configure one OCR provider key. For a shared local
network, also configure `ORDER_ROBOT_TOKEN` and enter the same token in the
Android server settings.

Start the server:

```powershell
py.exe .\server.py
```

The default server port is `8080`.

## Verification

Run the focused Python tests:

```powershell
py.exe -m unittest test_server_send_session.py test_server_training.py
```

Run Android unit tests and create a debug APK:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

Validate the AutoHotkey script with AutoHotkey v2 before testing inside
E-PLUS. Syntax validation does not replace a real E-PLUS smoke test.

## Release safety checklist

- Count every row in a long invoice manually after OCR.
- Verify page order, duplicates, and totals for a multi-page invoice.
- Pause and resume after several E-PLUS items without repeating the header or
  last item.
- Verify the running/paused lock and cancellation path.
- Check what happens to a partial invoice when E-PLUS closes unexpectedly.
- Increment Android `versionCode` and `versionName` only after the smoke tests.
- Keep generated builds, caches, logs, installers, and real secrets out of Git.

## Current Android version

- `versionName`: `1.7`
- `versionCode`: `8`

This repository contains source and test assets. Generated APKs and offline
installers should be published separately as release artifacts when required.
