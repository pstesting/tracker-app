# Tracker App

Android workout tracker for two participants, with offline session recording and Google Sheets as the shared review record.

## ET-0 connection prototype

This first increment establishes the Android project and the version 1.0 workbook contract. It provides:

- Explicit Google account selection, separate from the workout participant.
- Google Identity Services authorisation when a connection check is requested.
- A read check for the structured ET-0 workbook, including headers, stable IDs, numeric loads and per-side conventions.
- A temporary-tab write/read/delete check using synthetic results only.
- Durable recovery identifiers, ownership checks and verified cleanup after an interrupted request.

Actual workout logging, offline sessions and end-of-workout synchronisation follow in ET-1 onwards. The connection prototype requires internet. It does not write session-history tables.

## Build and run

Open this folder as a Gradle Android project in Android Studio, with JDK 17 or 21, Android SDK platform 36 and Build Tools 35.0.0. IntelliJ IDEA with compatible Android support can also open it.

```bash
./gradlew :sheets-core:test :app:lintDebug :app:assembleDebug
./gradlew :app:signingReport
./gradlew :app:installDebug
```

On Windows PowerShell, use `.\gradlew.bat` in place of `./gradlew`. The Gradle wrapper downloads Gradle 8.13 and verifies its pinned SHA-256. The app package is `uk.co.pstesting.tracker`, with minimum Android 9 (API 28).

Complete [Google setup](docs/GOOGLE_SETUP.md) before the phone's authorisation check. No OAuth secret or `google-services.json` is needed for this device-only authorisation flow.

Paste the ET-0 **test** workbook link into the app; workbook IDs and account details are runtime settings. Use the separately prepared ET-0 workbook containing the versioned `Guide`, `WorkoutPlan`, `Sessions`, `SessionExercises` and `Sets` tabs. The original wide-format tracker is not the connection-probe target.

## Structure

| Path | Purpose |
| --- | --- |
| `app/` | Compose connection screen, Google account/consent flow and private recovery storage |
| `sheets-core/` | JVM-testable workbook validation and temporary-tab probe |
| `contract/sheets-schema.json` | Shared headers, field mappings and enum definitions; no personal records |
| `docs/ET-0.md` | Confirmed requirements, increment boundaries and verification status |
| `docs/DEVICE_CHECKLIST.md` | Both-phone checks, cancellation, interrupted uploads and recovery |

No workbook URLs, personal workout data, access tokens or signing keys are included. Keep local exports/configuration in ignored `private/` files. Do not attach personal workbook data to public issues or CI logs.

## Verification

CI runs the JVM tests, Android lint and debug assembly. Device Google consent and access checks must be run against the actual Google Cloud OAuth registration and the chosen test workbook on each phone; a connector or fake transport cannot prove them.

See [device checklist](docs/DEVICE_CHECKLIST.md) for remaining acceptance checks.
