# Free Roam: bounded autonomous wake cycles

The backend can now wake independently of Android, review persistent context,
choose explore/research/reflect/rest with a model, perform one permitted web search,
and retain a journal and labeled memories. It is off by default. Android's
**Free Roam journal** provides pairing, inspection, permissions, and limits;
owner notes, interest/goal editing, and export remain available through the API.

The Android Room row remains canonical for the body. Pairing stores only its
original creation timestamp and fixed curiosity trait as identity metadata.
Energy, stimulation, mode, interaction history, and phone observations are rejected
by these APIs. Nothing from this store is added to the existing `/think` payload.
An authenticated pairing request is consent to persist those two identity fields.
Creation timestamps and identity IDs are not sent to the provider. Explicitly enabling
Free Roam additionally permits sending curiosity, interests, active goals, recent
notes, and recent experiences to OpenAI. Phone observations remain excluded.

## Storage and deployment

The implementation uses Python's standard library and the existing Docker image.
Set `FAMILIAR_DATA_DIR` to an existing absolute persistent directory. The fixed
database name is `free-roam.sqlite3`. An absent variable leaves these APIs unavailable
while `/health` and `/think` retain their existing behavior.

Initialization is a separate operator action. Runtime connections use SQLite
`mode=rw`, so a missing database or directory cannot create a replacement familiar.
Missing, corrupt, busy, or incompatible storage returns a sanitized HTTP 503.
`/health` checks the voice server; authenticated `GET /free-roam` checks cognitive
storage. Schema version 2 is explicit; there is no destructive migration fallback.

Existing schema-1 stores require an operator upgrade with all schema-1 writers stopped:

```sh
python free_roam.py migrate --data-dir /data --output /data/before-wake-cycles.sqlite3
```

This creates a consistent, non-overwriting backup before an additive transaction.
It preserves identity, controls, notes, goals, interests, and revision. Old notes
become `owner_note` records with no sources. Runtime never upgrades automatically,
and `init` refuses an old database instead of replacing it. A schema-2 migration
retry verifies the existing store without making another backup.
On Railway, deploy the new build first, then run the command through Railway SSH.
Until migration commits, its cognitive APIs and worker refuse schema 1 with 503;
the voice path can remain running. Do not leave an older process writing the same
volume during this operation. Verify pairing and disabled controls before opting in.

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
| `GET /free-roam/journal?after=0&limit=20` | Inspect real wake attempts, decisions, findings, costs, and failures |
| `GET /free-roam/journal?before=9223372036854775807&limit=20` | Newest entries first; use `nextCursor` as the next `before` value |
| `DELETE /free-roam/{memories,interests,goals,journal}/{id}` | Remove content; deleting an agent memory also redacts its journal entry and vice versa |
| `GET /free-roam/export` | Consistent JSON export of identity, settings, knowledge, audit, visible journal, and recent monthly accounting |
| `POST /free-roam/forget` | Erase cognitive content and prior audit, revoke web permission, and preserve identity |

Pairing accepts exactly `pairingId`, `createdAt`, and `curiosity`. The client must
generate and persist one canonical UUID v4 for `pairingId` before making requests.
Use the real existing Room creation timestamp (UTC epoch milliseconds) and curiosity,
not example values, when pairing production. The generated `familiarId` is stable
across restarts and backup recovery. A different pairing ID or changed identity
fields return HTTP 409; there is no API to replace the paired familiar. Retain
the pairing ID for retries. Android persists it before its first request.

The journal's optional `before` cursor reads descending sequence numbers strictly
below the cursor, excluding redacted content. It cannot be combined with `after`
and is rejected on other collections. Existing `after` clients retain ascending
behavior. `nextCursor` is null on the last page; otherwise it is the last item's
sequence. Each page carries the current revision so clients can reload rather
than combine pages across a concurrent wake or deletion. This requires no schema
change.

## Android inspection and control

Update the backend to this version before using the Android v0.3 journal. Open
**Free Roam journal** beneath the familiar snapshot; it reuses the saved voice
HTTPS URL and owner access token. No OpenAI key belongs on the phone.

1. Review and explicitly confirm **Pair existing familiar**. The app uploads only
   the Room row's original creation time and fixed curiosity. New pairing keeps
   Free Roam paused. An already matching remote profile can be adopted explicitly
   through **Connect existing pairing**.
2. Open **Permissions and limits**, choose the interval, daily wake count, and
   monthly API budget, then save. Web search requires its separate switch and
   a nonempty domain allowlist. Saving these controls does not start a paused agent.
3. Tap **Start Free Roam** and confirm the notice about sending cognitive context
   to OpenAI and paying for server wakes. **Pause Free Roam** is immediate; a
   request already sent may finish and remain billable. Server execution needs the
   persistent storage and running Railway service described above.
4. Inspect recent activity, decisions, source links, fixed failure descriptions,
   and conservative costs. **Load older activity** pages backward; refresh and
   reopening/resuming the journal fetch current state. There is no Android worker,
   polling loop, automatic notification, or offline journal cache.

Bindings are private preferences keyed by backend URL and original creation time.
They retain only the retry UUID and verified server familiar ID, not credentials
or journal text. A lost pairing response recovers the same identity. A missing
previously known server identity blocks replacement and requires restoring its
backup. Changing backend settings clears displayed content and discards old
responses. HTTP 409 reads back current state without replaying the write; refresh
and review before trying again. Failed reads leave controls disabled.

**Forget this entry** removes its generated experience as well as visible journal
content. **Forget all Free Roam memory** pauses execution and clears cognitive
content. Both require confirmation; identity, local body state, and content-free
spending receipts remain. Exports and backups may retain prior copies. Clearing
Android app data also deletes its local creature and pairing preferences, as before;
there is no body restore or cloud sync in this slice.

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

Notes added through the owner API are always labeled `owner_note`; callers cannot supply agent provenance,
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
Only an explicit authenticated `enabled: true` permits autonomous execution. The
worker checks the store every 60 seconds, so the first wake may begin within one
minute. To pause, set `enabled: false` using the current revision. Changing a
control or deleting content invalidates an in-flight wake's context. An already
sent request may finish and remain billable; its result is discarded. The worker
rechecks consent before each request and before remembering a result.

Web permission is separate from execution and requires a nonempty domain
allowlist. At most 20 public domain names are accepted, without schemes, ports,
paths, wildcard prefixes, or local/IP targets. Names are normalized to lowercase.
The only tool is OpenAI's hosted `web_search`, with these domain filters. An allowed
domain also permits its subdomains. The backend does not fetch arbitrary URLs,
follow redirects, run code, send messages, or give the model a policy-writing tool.
Every returned consulted URL and citation must be an HTTPS URL on the allowlist;
out-of-policy results are rejected. Enforcement of search access itself relies on
the provider's documented filters, rather than a local network firewall.

Wake intervals range from 6 to 168 hours; daily wake limits from 1 to 4. The monthly
API budget is an integer in cents from 0 to 10,000 (zero permits no spend). These
settings persist across restarts. Durable accounting is described below. The existing Listen
rate limiter remains separate, process-local, and unchanged.

Only authenticated owner endpoints write permission policy. The backend also
pauses execution if provider usage exceeds the reserved cost. The model can
produce a decision or finding, never a policy or body-state command.
Administrative audit retains at most 1,000 events containing
IDs, counts, and changed field names; it excludes note/goal text and credentials.

## Wake behavior and evidence

The existing Railway web process owns one background thread. It continues while
Android is closed; the Railway process must remain running, so disable service
sleeping. There is no second service, cron job, queue, or dependency. A durable
SQLite transaction claims one due wake and reserves its cost. Concurrent workers
cannot claim the same wake. A server restart does not reset limits or trigger a
catch-up burst: at most one wake runs, and the next interval starts at its claim.
Daily limits and accounting months use UTC and the wake's start time.

One stateless decision call receives the fixed curiosity trait, ten interests,
three active goals, up to eight recent memories, and four recent journal entries.
Text is shortened and older context omitted when necessary to keep the complete
request below 24 KiB. Owner notes and prior model text are identified as data.
The model chooses `rest`, `reflect`, `explore`, or `research`; Python does not
schedule a scripted sequence of those actions. Rest creates only a journal entry.
Reflection creates a subjective `reflection` memory without factual verification.
Without web permission, only rest and reflection are accepted.

Explore/research add one stateless request containing the chosen topic/question
and one hosted search, with `max_tool_calls: 1`, low search context, and no parallel
tool calls. The complete consulted-source list is requested. A `finding` requires
one completed search and citations whose URLs appear in that list and satisfy the
allowlist. The summary and source title/URL pairs are persisted together. Findings
are model interpretations of consulted sources, not independently verified facts.
The model cannot mark owner goals completed or rewrite owner interests.

Every started wake has a journal receipt with time, status, selected action/topic/question,
decision reason, summary, source links, call count, estimated charged micro-dollars,
and a fixed error code when needed. The decision reason is a short explanation,
not private chain of thought. Errors never contain provider bodies or credentials.
Fixed error codes distinguish HTTP status failures, timeouts, network errors, and
invalid decision/source evidence without storing the provider's error text.
Snapshot `execution.nextWakeAt` is the earliest interval boundary; enabled state,
daily/monthly limits, capacity, and a running server determine whether it can run.
New memories enter future decision context, so real findings and reflections can
influence subsequent choices. Retrieval is bounded recency, not semantic search.

Claims expire after five minutes. An interrupted worker is recorded as interrupted
and is never replayed. Failures also count toward the daily limit; there are no
automatic provider retries. Missing storage leaves the existing voice path working.
At 1,000 memories or 1,000 wake receipts, new wakes stop instead of silently losing
history. Memory deletion frees memory slots; receipt archival is future work.

## Model selection and cost accounting

Both Listen and Free Roam read Railway's `OPENAI_MODEL`; a blank or absent value
defaults to `gpt-6.1-sol`. Changing this variable and deploying the Railway variable
changes switches the model without another Android update. Free Roam accepts only
models listed in `model_config.py`, with reviewed prices and filtered-search support:

| Model | Input / output per million tokens (Standard) | Entire wake reservation |
|---|---|---|
| `gpt-6.1-sol` | $2.00 / $10.00 | $0.50 |
| `gpt-6-luna` | $0.10 / $0.50 | $0.05 |

Unknown models block autonomous calls without changing identity, memories, controls,
or spending. `GET /free-roam` reports `runtime.model`, `modelStatus`, execution
availability, and `wakeReservationMicrousd`; Android explains blocked execution.
New model support requires checking Responses structured output, low reasoning,
domain-filtered `web_search`, prices, returned model identity, and the reservation
bound, then updating the small registry and tests. Listen retains its generic
Responses text path for other models, whose API defaults may need adjustment.

Each autonomous request caps output at 2,048 tokens, including reasoning, and uses
low reasoning. Search uses `return_token_budget: "default"`, one tool call, and low
search context. Before any call, SQLite reserves the model's entire wake amount.
Admission requires that much remaining monthly budget. The reservation covers two
24-KiB request byte bounds plus framing allowances, two output caps, a conservative
131,072-token search-context allowance, and one $0.01 tool fee. Reservations are
temporary admission estimates; a $0.50 reservation is not the expected wake price.

Completed responses settle from reported input/output usage at the selected model's
rates, rounded up to a micro-dollar, plus $0.01 for a search. Reasoning is included
in output tokens; cache discounts are ignored. The old fixed 8,000-token mini-model
search rule does not apply to these models. Rates and tool constraints were checked
on October 10, 2026; review them when model aliases or prices change. See the official
[Sol model page](https://developers.openai.com/api/docs/models/gpt-6.1-sol),
[Luna model page](https://developers.openai.com/api/docs/models/gpt-6-luna),
[search pricing](https://developers.openai.com/api/docs/pricing), and
[web-search controls](https://developers.openai.com/api/docs/guides/tools-web-search).

If usage is unknown, a provider timeout occurs, or the process disappears, the full
original reservation remains charged. Existing receipts retain their original
amounts; this update does not reprice old failures. If usage exceeds the reservation,
the reported conservative estimate is recorded and execution is paused. Costs cannot
be undone after a request is sent. This is an application admission limit and an
estimate, not a provider billing guarantee; retain provider spending controls.
Railway hosting and the existing Listen requests are separate from this allowance.

## Export, forgetting, and recovery

JSON export is for inspection and portability; it is not an import protocol.
Forgetting requires both the latest `expectedRevision` and the exact confirmation
`"forget-cognitive-memory"`. It clears notes, interests, goals, and prior audit,
adds a content-free forgetting event, and disables execution/web permission.
The paired identity, creation time, curiosity, and configured limits remain.
Individual deletion also removes the note from later exports; audit never contains
its text. Deleting a generated memory also redacts its journal content. Content-free
wake receipts and monthly totals remain internally so forgetting cannot reset
scheduling or spending. Deleting one owner note does not erase earlier generated
memories derived from it; use full forgetting to erase all cognitive content.
An in-flight wake may add a content-free settlement audit
event, but cannot restore forgotten text. SQLite secure deletion is enabled. Previously downloaded exports and
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
the original identity, knowledge, permissions, wake schedule, and accounting.
Restoring an older snapshot also rolls back its accounting; reconcile subsequent
spend with the provider before re-enabling a restored store.

## Verification

Run the backend suite from the repository root:

```powershell
python -B -m unittest discover -s backend -v
```

Tests use temporary databases, localhost HTTP servers, and fake provider responses.
They make no paid calls. They cover identity preservation, restart persistence,
stale/concurrent transactions, permission validation, provenance, pagination,
forgetting, backup recovery, storage failure, and unchanged legacy Listen behavior.
They also exercise autonomous decisions, real-source provenance requirements,
durable claims and costs, expiry without replay, in-flight consent changes, and
the schema-1 migration. Live provider compatibility and production activation
remain an operator check; no deployment or paid API test is part of this commit.
