# Pocket Familiar

An Android prototype about a small creature that continues to exist between visits.
v0.1a tests persistence, autonomous sleep/wake cycles, and awareness of phone context.
There is one creature, one screen, and one interaction: a poke.

The database is canon. The simulation owns state. Behavior is derived from that state;
phone observations do not change it. There is no AI integration.

## Run

Requires JDK 17 or 21 and an Android SDK with API 35 and Build Tools 35.0.0.
Open this directory in a compatible Android Studio and install the requested SDK
packages. Use an Android 8.0 (API 26) or newer device/emulator.

For command-line builds, set `ANDROID_HOME` or create ignored `local.properties`:

```properties
sdk.dir=C:/path/to/Android/Sdk
```

From PowerShell:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
.\gradlew.bat installDebug
```

On macOS/Linux, use `./gradlew` (make it executable if needed). The APK is written to
`app/build/outputs/apk/debug/app-debug.apk`.

Dependencies are pinned: Kotlin 2.1.20, Compose BOM 2025.04.01, Room 2.7.2,
coroutines 1.10.2, AGP 8.9.2, and Gradle 8.11.1. The wrapper verifies its downloaded
distribution against a pinned SHA-256 checksum. The versions form a conservative
API 35 prototype baseline. They are not a claim of current store-release readiness.

## Use

The main screen shows a drawn placeholder creature, its behavior, local time of day,
and phone battery/charging context. Tap the creature for a small twitch. A poke adds
stimulation and records an interaction; it does not restore energy or wake a resting
creature.

Expand **Developer details** to inspect canonical values, derived behavior,
timestamps, interaction count, and elapsed time processed by the latest update.
Resume the app or poke to obtain a fresh observation. There are no background jobs,
timers, notifications, or prominent meters.

## Architecture

One app module, with separate packages under `dev.pocketfamiliar`:

```text
simulation/   Plain Kotlin state, tuning rules, elapsed-time engine, derived behavior
persistence/  Room entity/DAO/database and transactional repository
perception/   Independent time and battery sources, combined observation snapshot
ui/           ViewModel, Compose screen, and transient visual reaction
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
primitive fields and enum names can be serialized for a future export. No brain
interface is needed in this version. Future interpretations can consume this state
and an environment snapshot without owning either.

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

Validated on an AOSP Android 15 / API 35 emulator: APK assembly and lint pass,
27 JVM tests pass, and all 3 Room integration tests pass. A launch/poke/force-stop/
reopen check also preserved creation and interaction history, advanced real elapsed
time, and displayed a changed battery observation without changing creature energy
to match it. Physical-device usability is still worth checking.

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

This prototype intentionally excludes chat, feeding, health, currencies, quests,
accounts, cloud sync, and AI. Its open question is experiential: do persistence,
autonomous cycles, and simple situated observations make the creature feel continuous?
