# Google authorisation setup

## Cloud project

1. Create/select a Google Cloud project and enable the Google Sheets API.
2. Configure Google Auth Platform branding, audience and data access. For personal Google accounts, use External with Testing status and add both intended account emails as test users. A Workspace-only Internal audience excludes personal accounts.
3. Declare `https://www.googleapis.com/auth/spreadsheets` for this connection prototype. The app explains this broader spreadsheet access before consent. It requests no general Drive scope, server auth code or refresh token.
4. Run `./gradlew :app:signingReport` (Windows: `.\gradlew.bat :app:signingReport`). Register an **Android** OAuth client with package `uk.co.pstesting.tracker` and the actual debug signing certificate's SHA-1.
5. Install the locally built APK on both phones using the same persistent local debug key, or register a separate Android OAuth client for each actual package/certificate combination. Rebuilding on another PC or using an ephemeral CI key changes the fingerprint. Do not commit or share the private key.
6. Share the chosen ET-0 test workbook as Editor with each Google account that will run a write check. Ownership may remain with a different personal/work account. The original tracker and ET-0 test copy have independent sharing.

Device-only `AuthorizationClient` access does not need a web client, backend secret, server OAuth exchange or Firebase configuration. The Android package and certificate registration identify the app. The Google account selected on the phone must have workbook access; it is separate from the participant being recorded.

## On the phones

Open Tracker App, paste the ET-0 test workbook link, choose the intended account, and run the read check. Google consent is requested at that action. Then run the temporary write/cleanup check and confirm you selected the test copy.

The prototype intentionally uses the spreadsheets scope to prove access to an existing shared workbook selected by pasted link. A pasted ID alone does not grant `drive.file` access. Before finalising ET-4, assess a supported per-file picker/grant route with `drive.file` on both phones, or deliberately retain/document spreadsheets access for the personal app. This prototype does not claim a file-scoped grant.

Tokens live only in memory during a check. Every new check asks AuthorizationClient for current authorisation; denied, expired or revoked access requires reconnecting. Do not paste access tokens into the app, source, chat, issues or logs.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Consent fails immediately | Exact application ID and SHA-1; correct Cloud project and Android client; Google Play services available |
| Account cannot authorise | External test audience, named test users and any Workspace admin restrictions |
| HTTP 403 | Sheets API enabled; requested scope granted; chosen account has Editor sharing |
| HTTP 404 | Correct workbook link and selected account's access |
| Unsupported schema | Use the structured ET-0 test workbook, not the original wide-format tracker |
| Temporary check unresolved | Use Recover temporary tab; inspect the recorded workbook/title if cleanup remains uncertain |

See [device checklist](DEVICE_CHECKLIST.md).

## Primary documentation

- [Android Google-data authorisation](https://developer.android.com/identity/authorization)
- [AuthorizationRequest account selection](https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationRequest.Builder)
- [Google API Android client identity](https://developers.google.com/android/guides/client-auth)
- [Google Sheets scopes](https://developers.google.com/workspace/sheets/api/scopes)
- [Sheets batch updates](https://developers.google.com/workspace/sheets/api/reference/rest/v4/spreadsheets/batchUpdate)
