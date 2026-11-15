# ET-0 device acceptance

Use the separately prepared ET-0 test copy. Do not put personal workbook IDs, account emails or workout results in this public repository's test reports/issues.

| Check | Galaxy S23 Ultra | Pixel 8 Pro |
| --- | --- | --- |
| Locally signed debug APK installs and launches | Pending | Pending |
| Choose the intended personal/work Google account | Pending | Pending |
| Accept spreadsheet consent; read check validates the expected exercise count | Pending | Pending |
| Temporary write/read passes, including numeric weights and both sides | Pending | Pending |
| Temporary tab removed and deletion confirmed; no history rows created | Pending | Pending |
| Cancel account selection/consent; app remains usable and makes no write | Pending | Pending |
| Revoke access in Google account settings, then reconnect | Pending | Pending |
| Rotate/background during consent and check; no duplicate check starts | Pending | Pending |
| Interrupt connectivity during write; resolve owned tab through recovery | Pending | Pending |
| Restart/force-stop during an unresolved check; recovery record survives | Pending | Pending |

1. Build locally after registering the debug signing SHA-1. Use that same APK on both phones if sharing the signing identity.
2. Share the ET-0 test workbook with the accounts selected on those phones. Start with a read check and compare the exercise count with the workbook.
3. Run one temporary write check at a time initially. Confirm the success message includes verified cleanup. Inspect the workbook's empty session-history tables.
4. Cancel consent on an account that has not already granted access, or revoke access first in the account's third-party-app settings. Confirm the app reports cancellation without starting a Sheets check.
5. During a temporary write check, disable connectivity or force-stop the app. Reopen, choose the same authorised account and use Recover temporary tab. If the tab is not observed, inspect the recorded workbook/title before explicitly clearing the recovery record. A request whose response was lost may complete later.
6. Record pass/fail notes privately. ET-0 remains pending until both phones pass the applicable checks.

The prototype has no workout database yet. Offline recording and lossless behaviour for actual saved workouts are ET-1/ET-4/ET-5 checks. This checklist does not claim they have passed.
