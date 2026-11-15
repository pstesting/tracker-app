# ET-0: workbook contract and Android connection proof

## Confirmed requirements

- One session per participant per gym visit; one phone may record both people in distinct sessions.
- Workouts and exercise definitions are maintained in Sheets. The app will cache a snapshot for each session.
- Sets save immediately on the phone; finished sessions synchronise when online. Offline workout recording begins in ET-1, with upload in ET-4.
- Exercises can be performed in any order while retaining planned pairings and actual order.
- Dumbbell loads are per implement except the dumbbell step-up, which records combined external load. Barbells include the bar. Bodyweight records zero external load with manual progression initially.
- Unilateral exercises record separate left/right results. Six side records represent three logical sets.
- The next-session suggestion increases by a configurable default of 1 kg after three working sets of at least 12 at the same load. Both sides must qualify for unilateral exercises. Controls will provide -1, +1, +2 and +5 kg plus direct fractional entry.
- Suggestions use actual completed results, including local sessions awaiting upload. They are calculated once from those results, not cumulatively each time a screen opens.
- No historical-session migration is required for the fresh schedule.

## Workbook contract

`contract/sheets-schema.json` is the single bundled definition of the ET-0 version 1.0 headers and enumerations. The schema version is read from `Guide!B4`.

| Tab | Row meaning | Managed by |
| --- | --- | --- |
| `WorkoutPlan` | Participant/exercise assignment within a workout, with stable IDs and load/repetition settings | Sheets |
| `Sessions` | One participant's gym visit | App |
| `SessionExercises` | Exercise occurrence and configuration snapshot within a session | App |
| `Sets` | One set and side, with actual weight and reps | App |

Match columns by header and records by stable ID. Row positions are transient. New rows need identity assignment before they can start sessions; explicit refresh/ID assignment is ET-4 work. A variation with a different equipment, load or repetition convention needs a distinct Exercise ID.

Zero load/repetitions, blank values, skipped exercises and unfinished sets are different states. Never convert a missing weight into zero or fabricate zero-rep records for skipped work. Three working sets at 12 qualify for automatic progression; incomplete sessions, missing sides, unequal loads and warm-ups do not.

The connection prototype reads the defined tabs and validates the plan. It creates one uniquely named temporary tab containing synthetic typed results, then checks the exact values and removes only the owned tab. It does not upload a workout, test concurrent session synchronisation or create a workbook.

## Reliability boundaries

The probe saves workbook ID, random tab ID, title and run marker durably **before** its single add/write batch. A failed or missing response never causes automatic replay of that mutation. Cleanup requires matching ID, title and ownership marker, then verifies deletion. The journal survives app/process restart; tokens do not.

If a mutation is not observed, its original request could still finish later. The app retains the recovery record and blocks new write probes. Retry recovery or inspect that exact test workbook and explicitly clear the record only after checking the tab is absent. Deletion with a missing response also remains unresolved until inspected/recovered.

For ET-4, retain an outbox with operation and entity IDs, reconcile uncertain outcomes, batch complete session data before publishing completion, and enforce one writer phone per active participant/session. Do not merge overlapping sessions by date. Sheets revisions alone are not atomic compare-and-swap locks; same-session concurrent editing would need a serialising service.

## Increment boundaries

| Increment | Deliverable |
| --- | --- |
| ET-0 | Workbook schema/seed preparation, connected-account checks, Android connection prototype and actual-device authorisation proof |
| ET-1 | Offline database, profiles, cached workouts and resumable sessions |
| ET-2 | Flexible gym entry, side-specific sets and recording both participants |
| ET-3 | Previous-performance suggestions, progression and weight controls |
| ET-4 | End-of-workout synchronisation, retries/reconciliation, ID refresh and review views |
| ET-5 | Gym trial on both phones, including offline and one-phone sessions |

## Verification status

The preceding workbook preparation completed 22 local data-contract/probe checks and a connected-account write/read/delete check against a separate test workbook. Those checks were not Android OAuth tests.

This repository contains generic contract definitions and synthetic test fixtures. Private seed rows, workbook links and connected-account evidence remain outside the public source tree. Its JVM tests exercise real JSON shapes with a fake Sheets transport; they do not make Google requests.

Actual consent, shared-file read/write access, denial/revocation and interrupted-check recovery remain to be confirmed on Galaxy S23 Ultra and Pixel 8 Pro after OAuth setup. ET-0 must not be called fully complete until those checks pass. Select and share the long-term workbook separately before ET-4 enables real session synchronisation.
