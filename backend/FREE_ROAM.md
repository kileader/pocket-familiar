# Free Roam: persistent memory and controls

This is the first implementation slice, not the autonomous agent yet. It adds
SQLite persistence and authenticated owner APIs for pairing, notes, interests,
research goals, permission settings, export, and forgetting. There is no scheduler,
tool runner, paid research request, Android pairing client, or Android journal UI.

The Android Room row remains canonical for the body. Pairing stores only its
original creation timestamp and fixed curiosity trait as identity metadata.
Energy, stimulation, mode, interaction history, and phone observations are rejected
by these APIs. Nothing from this store is added to the existing `/think` payload.
An authenticated pairing request is explicit consent to persist those two identity
fields on the owner backend; they are not forwarded to OpenAI.

## Storage and deployment

The implementation uses Python's standard library and the existing Docker image.
Set `FAMILIAR_DATA_DIR` to an existing absolute persistent directory. The fixed
database name is `free-roam.sqlite3`. An absent variable leaves these APIs unavailable
while `/health` and `/think` retain their existing behavior.

Initialization is a separate operator action. Runtime connections use SQLite
`mode=rw`, so a missing database or directory cannot create a replacement familiar.
Missing, corrupt, busy, or incompatible storage returns a sanitized HTTP 503.
`/health` checks the voice server; authenticated `GET /free-roam` checks cognitive
storage. Schema version 1 is explicit; there is no destructive migration fallback.

For local development, use an ignored directory and synthetic identity in a
separate test database:

```powershell
$dataDir = Join-Path (Get-Location) '.tooling\free-roam-data'
New-Item -ItemType Directory -Force -Path $dataDir | Out-Null
python backend/free_roam.py init --data-dir "$dataDir"
$env:FAMILIAR_DATA_DIR = $dataDir
# Set the existing OPENAI_API_KEY and FAMILIAR_ACCESS_TOKEN before starting the gateway.
python backend/server.py
```

On Railway, attach one dedicated volume at `/data`, set
`FAMILIAR_DATA_DIR=/data`, and keep one replica. A volume-backed service has brief
deployment downtime and cannot use replicas. See the
[Railway volume reference](https://docs.railway.com/volumes/reference).

The Docker image runs as UID/GID `65534`. Railway mounts volumes with root
ownership. For initial provisioning, temporarily set `RAILWAY_RUN_UID=0`, deploy,
and use Railway SSH in the service to run:

```sh
python free_roam.py init --data-dir /data
chown 65534:65534 /data /data/free-roam.sqlite3
```

Then remove `RAILWAY_RUN_UID`, redeploy under the image's non-root user, and verify
authenticated `GET /free-roam`. Initialization verifies an existing database and
does not reset it. Never use provisioning to hide missing or corrupt production
data; restore the intended backup instead. Volume ownership guidance comes from
[Railway's volume documentation](https://docs.railway.com/volumes#permissions).

## Owner API

Every endpoint below requires `Authorization: Bearer FAMILIAR_ACCESS_TOKEN`,
including reads and export. Use the existing Railway HTTPS domain. Writes use
`Content-Type: application/json`; request bodies are limited to 16 KiB, and unknown
fields, duplicate JSON keys, nonfinite numbers, and ambiguous request framing are
rejected. Responses use `Cache-Control: no-store`. Neither the access token nor
the provider key is stored in the cognitive database or export.

| Method and path | Behavior |
|---|---|
| `GET /free-roam` | Profile, controls, revision, interests, goals, memory count, and runtime capabilities |
| `POST /free-roam/pair` | Pair the existing familiar; matching retries return the same identity |
| `POST /free-roam/controls` | Update owner permission and limit settings |
| `POST /free-roam/memories` | Append an immutable owner note; matching ID/content retries are idempotent |
| `POST /free-roam/interests` | Create or update an interest by ID |
| `POST /free-roam/goals` | Create or update a research goal by ID |
| `GET /free-roam/memories?after=0&limit=20` | Read notes using sequence cursors; maximum page size 50 |
| `GET /free-roam/audit?after=0&limit=20` | Inspect administrative changes, not agent experiences |
| `DELETE /free-roam/{memories,interests,goals}/{id}` | Remove an entry |
| `GET /free-roam/export` | Consistent JSON export of identity, settings, knowledge, and audit |
| `POST /free-roam/forget` | Erase cognitive content and prior audit, revoke web permission, and preserve identity |

Pairing accepts exactly `pairingId`, `createdAt`, and `curiosity`. The client must
generate and persist one canonical UUID v4 for `pairingId` before making requests.
Use the real existing Room creation timestamp (UTC epoch milliseconds) and curiosity,
not example values, when pairing production. The generated `familiarId` is stable
across restarts and backup recovery. A different pairing ID or changed identity
fields return HTTP 409; there is no API to replace the paired familiar. Retain
the pairing ID for retries. Android wiring will be added in the app slice.

After pairing, mutations require the `expectedRevision` from the latest response.
Every effective change increments the global revision in the same SQLite
transaction as its audit event. Stale writes return 409, so fetch current state
and resolve the change rather than overwriting newer data. Reads are transactionally
consistent. Pairing retries and identical note retries do not increment revision.

For example, a note request has this shape (generate a new UUID for each note):

```json
{
  "expectedRevision": 1,
  "id": "c4265be1-60a0-4197-94d9-bf1c37fda7bc",
  "topic": "animal navigation",
  "text": "I would like the familiar to investigate how migrating animals navigate."
}
```

Notes are always labeled `owner_note`; callers cannot supply agent provenance,
verification, source metadata, or fabricated experience timestamps. Notes require
`topic` (at most 80 characters) and `text` (at most 2,000). At most 1,000 notes are
kept; reaching the limit requires explicit export/deletion rather than silent loss.
Notes are immutable except for deletion. A reused ID with different content is a
conflict.

Interest requests contain `expectedRevision`, `id`, `topic`, and `priority` (1–5).
At most ten interests are kept; normalized topics are unique without regard to case.
Goal requests contain the same fields plus `question` (at most 500 characters) and
`status` (`active`, `paused`, or `completed`). At most three goals may be active;
up to 100 total goals retain completed/paused history. A resource ID is a canonical
UUID v4. Deletion takes a body containing only `expectedRevision`.

## Permissions and limits

New stores have these controls:

```json
{
  "enabled": false,
  "webEnabled": false,
  "allowedDomains": [],
  "wakeIntervalHours": 6,
  "dailyWakeLimit": 4,
  "monthlyBudgetCents": 500
}
```

`POST /free-roam/controls` takes `expectedRevision` and one or more of these fields.
The build rejects `enabled: true` with HTTP 409 because autonomous execution is
not implemented. Runtime capabilities explicitly report that execution and web
tools are unavailable. No control change causes a model call.

Web permission can be prepared independently, but requires a nonempty domain
allowlist. At most 20 public domain names are accepted, without schemes, ports,
paths, wildcard prefixes, or local/IP targets. Names are normalized to lowercase.
These are stored permissions for the later tool runner, not an active network
firewall in this slice.

Wake intervals range from 6 to 168 hours; daily wake limits from 1 to 4. The monthly
API budget is an integer in cents from 0 to 10,000 (zero permits no spend). These
settings persist across restarts. Actual wake accounting, cost reservations, and
provider-usage reconciliation belong to the execution slice. The existing Listen
rate limiter remains separate, process-local, and unchanged.

Only authenticated owner endpoints write policy. There is no model-facing mutation
or tool interface. Administrative audit retains at most 1,000 events containing
IDs, counts, and changed field names; it excludes note/goal text and credentials.

## Export, forgetting, and recovery

JSON export is for inspection and portability; it is not an import protocol.
Forgetting requires both the latest `expectedRevision` and the exact confirmation
`"forget-cognitive-memory"`. It clears notes, interests, goals, and prior audit,
adds one content-free forgetting event, and disables execution/web permission.
The paired identity, creation time, curiosity, and configured limits remain.
Individual deletion also removes the note from later exports; audit never contains
its text. SQLite secure deletion is enabled. Previously downloaded exports and
volume backups may retain older content and need separate retention management.

Create a consistent SQLite backup while the service is running:

```sh
python free_roam.py backup --data-dir /data --output /data/free-roam-backup.sqlite3
```

The output must be an absolute path in an existing directory. Existing backups
and the live database cannot be overwritten. Use dated filenames for subsequent
backups and keep a protected copy outside the live volume. On Railway, volume
backups are also available; see [Railway backups](https://docs.railway.com/volumes/backups).

To restore, stop the service, copy a verified SQLite backup into the intended
data directory as `free-roam.sqlite3`, preserve UID/GID ownership, and restart.
Never restore a raw live-file copy made during a transaction. The regression suite
backs up, changes the live store, restores into a separate directory, and verifies
the original identity, knowledge, and permissions.

## Verification

Run the backend suite from the repository root:

```powershell
python -B -m unittest discover -s backend -v
```

Tests use temporary databases, localhost HTTP servers, and fake provider responses.
They make no paid calls. They cover identity preservation, restart persistence,
stale/concurrent transactions, permission validation, provenance, pagination,
forgetting, backup recovery, storage failure, and unchanged legacy Listen behavior.
