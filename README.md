# Pocket Familiar

An Android prototype about a small creature that continues to exist between visits.
v0.2a adds an optional AI thought to the v0.1b visual expression and v0.1a persistence,
autonomous sleep/wake cycles, and awareness of phone context.
There is one creature, one screen, a poke, and an on-demand **Listen** action.

The database is canon. The simulation owns state. Behavior is derived from that state;
phone observations do not change it. AI interprets a read-only snapshot and returns
words only. It cannot change state. The creature works offline; listening needs a
configured hosted backend and internet.

## Run

Requires JDK 17 or 21 and an Android SDK with API 35 and Build Tools 35.0.0.
Open this directory in a compatible Android Studio and install the requested SDK
packages. Use an Android 8.0 (API 26) or newer device/emulator.

For command-line builds, set `ANDROID_HOME` or create ignored `local.properties`:

```properties
sdk.dir=C\:/path/to/Android/Sdk
```

From PowerShell:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
.\gradlew.bat installDebug
```

On macOS/Linux, use `./gradlew` (make it executable if needed). The APK is written to
`app/build/outputs/apk/debug/app-debug.apk`.

Dependencies are pinned: Kotlin 2.1.20, Compose BOM 2025.04.01, Room 2.7.2,
coroutines 1.10.2, AGP 8.13.2, and Gradle 8.13. The wrapper verifies its downloaded
distribution against a pinned SHA-256 checksum. The versions form a conservative
API 35 prototype baseline. They are not a claim of current store-release readiness.

## Use

The main screen shows a drawn creature, its behavior, local time of day,
and phone battery/charging context. Tap the creature for a brief reaction. A poke adds
stimulation and records an interaction; it does not restore energy or wake a resting
creature.

Each behavior has a visual pose: resting curls down with closed eyes and slow
breathing, drowsy slouches with heavy eyelids, restless fidgets and looks around,
watchful stands upright and blinks, and lively smiles with a small bob. Pokes cause
a tiny resting twitch, a sleepy stir, or an awake flinch/hop. These are presentation
effects driven by derived behavior. They never mutate canonical state. Idle motion
is active only while the screen is resumed; old poke reactions do not replay when
returning or recreating the screen.

<p>
  <img src="docs/screenshots/resting.png" alt="The creature curled down and resting" width="240">
  <img src="docs/screenshots/lively.png" alt="The upright creature smiling while lively" width="240">
</p>

Emulator captures of resting and lively poses using controlled test states.

Expand **Developer details** to inspect canonical values, derived behavior,
timestamps, interaction count, and elapsed time processed by the latest update.
Resume the app or poke to obtain a fresh observation. There are no background jobs,
simulation timers, notifications, or prominent meters.

## Architecture

One app module, with separate packages under `dev.pocketfamiliar`:

```text
simulation/   Plain Kotlin state, tuning rules, elapsed-time engine, derived behavior
persistence/  Room entity/DAO/database and transactional repository
perception/   Independent time and battery sources, combined observation snapshot
ui/           ViewModel, Compose screen, and transient visual reaction
brain/        Read-only context, text response, HTTPS gateway client, private settings
backend/      Python voice gateway and Railway deployment files (outside app module)
```

`FamiliarApplication` constructs shared dependencies. `MainActivity.onResume()` asks
the ViewModel to refresh. The ViewModel serializes UI operations with a coroutine
mutex; the repository also uses a Room transaction for each read/advance/write.
Every poke advances elapsed time before applying the interaction and commits before
showing the reaction. Concurrent access cannot overwrite a previous interaction.

The only Room table is `creature`, with a single row identified by `1`:

| Column | Type | Meaning |
|---|---|---|
| energy | Float | 0–100 |
| stimulation | Float | 0–100; simulation floor 15 |
| mode | String | `AWAKE` or `RESTING` |
| curiosity | Float | Fixed personality trait, 0–1 |
| createdAt | Long | UTC epoch milliseconds |
| lastUpdatedAt | Long | UTC epoch milliseconds |
| lastInteractionAt | Long? | Null before the first poke |
| interactionCount | Long | Total persisted pokes |

Behavior, environmental observations, animation state, and debug elapsed time are
not persisted. `CreatureState` has no Android, Room, UI, or model dependency; its
primitive fields and enum names can be serialized for a future export. `CreatureBrain`
consumes state and an environment snapshot and returns a short text response. Network
work runs outside the simulation mutex, and responses from outdated snapshots are
discarded. The Room schema and simulation rules are unchanged.

Database errors are surfaced for retry; the app does not reset or recreate a creature
to hide them. Schema export is enabled. Future schema changes require explicit
migrations; destructive migration fallback is not enabled. Android cloud backup is
disabled for this local prototype. Clearing app data or uninstalling deletes the
creature; closing, force-stopping, or updating the app preserves it.

## Simulation rules

All tuning values live in `simulation/SimulationRules.kt`.

| Rule | v0.1a value |
|---|---:|
| Initial energy / stimulation / curiosity | 80 / 50 / 0.65 |
| Awake energy loss | 3/hour |
| Awake stimulation loss | 1.5/hour |
| Stimulation floor | 15 |
| Sleep threshold | energy ≤20 |
| Resting energy recovery | 10/hour |
| Wake threshold | energy ≥80 |
| Stimulation while resting | Held steady |
| Poke stimulation increase | +8, capped at 100 |

Behavior precedence is deliberate:

1. `RESTING` if mode is resting.
2. `DROWSY` if awake and energy ≤35.
3. `RESTLESS` if awake and stimulation <30.
4. `LIVELY` if awake, energy ≥60, and stimulation ≥60.
5. `WATCHFUL` otherwise.

Curiosity is fixed and does not yet alter behavior. Low stimulation causes no damage
or escalating penalty; absence simply lets ordinary cycles continue.

The simulator uses elapsed UTC time, not background execution. It processes each
energy threshold and the remaining interval. Inside the normal 20–80 energy range,
every 26-hour period returns to the same energy and mode (20 hours awake, 6 resting).
Whole cycles can therefore be skipped with their awake time accounted for in
stimulation decay. This keeps a years-long absence bounded in work. Calculations use
`Double`, then store `Float`; the `Long` interval remainder preserves the phase even
for very large timestamps.

From the initial state, 20 hours gives resting energy 20; 23 hours gives resting
energy 50; 26 hours gives awake energy 80; 27 hours gives awake energy 77.

If the wall clock moves backward, elapsed time is zero and the previous update
timestamp is retained. Interaction timestamps use that same logical time. A forward
clock change is indistinguishable from real elapsed time in this offline prototype.
No server clock or tamper prevention is added.

## Perception

Time is observed in the phone's current local zone. Coarse periods are night
(22:00–05:59), morning (06:00–11:59), afternoon (12:00–17:59), and evening
(18:00–21:59). Battery readings come from Android's sticky battery broadcast and
account for its reported scale. Unknown readings remain unknown. Charging/full
status is separate from percentage.

These sources require no runtime permissions. New sensors can follow the independent
source pattern and extend the observation snapshot. They should not mutate creature
state or introduce a parallel simulation.

## Verification

v0.1b: APK assembly, lint, and 27 JVM tests pass. All five poses were inspected on
an AOSP Android 15 / API 35 emulator. Updating the installed app preserved the
existing creature, and a resting poke persisted without waking it.

The v0.1a baseline also passed all 3 Room integration tests. A launch/poke/force-stop/
reopen check preserved creation and interaction history, advanced real elapsed
time, and displayed a changed battery observation without changing creature energy
to match it. Physical-device usability is still worth checking after the visual update.

JVM tests exercise rates, threshold precedence, sleep/wake boundaries, multiple
offline cycles, long intervals, segmented updates, clock rollback, and resting
pokes. Perception tests cover coarse time and battery mapping.

Room integration tests use a real on-device database, close/reopen it, check offline
catch-up and resting pokes, and verify concurrent interactions are not lost:

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

Manual acceptance check on a device:

1. Launch and expand Developer details. Confirm one creature and initial state.
2. Poke. Confirm the twitch, stimulation increase, and count/timestamp update.
3. Force-stop and relaunch. Confirm identity timestamps and interactions persist.
4. Leave the app closed, return later, and inspect the elapsed-time update.
5. Compare time/battery/charging context against the phone, then resume or poke.
6. Use simulation tests to verify full sleep/wake cycles without waiting a day.

## AI voice setup

Deploy the small gateway on Railway using [the backend instructions](backend/README.md).
Keep the OpenAI API key in Railway variables. Configure the HTTPS URL and a separate
personal access token in the app's developer details, then tap **Listen** to display
one short thought. This is text, not audible speech or chat. Requests happen only on
that action; phone app activity is not observed yet. Thoughts are not persisted.

v0.2a: APK assembly and lint pass, along with 30 JVM tests, 11 backend tests, and
6 instrumented tests on the dedicated Android 15 emulator (3 voice, 3 Room).
Voice tests use fake model responses. The first paid response and Railway deployment
still need to be verified after the backend variables and phone settings are supplied.

This prototype intentionally excludes chat, feeding, health, currencies, quests,
accounts, and cloud sync. Its open question is experiential: do persistence,
autonomous cycles, and simple situated observations make the creature feel continuous?
