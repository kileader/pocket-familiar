# Pocket Familiar voice gateway

Small personal prototype using Python's standard library and OpenAI's Responses API.
The Android app owns the creature and SQLite database. This service receives an
observation and returns text with optional discovery ID and source metadata.
It has no creature database or state commands. The simulation remains on the phone.

## Deploy on Railway

1. Create an empty Railway project and an empty service named `familiar-voice`.
2. Connect `kileader/pocket-familiar`, branch `main`, in the service source settings.
3. Set **Root Directory** to `/backend`. Railway detects its `Dockerfile`.
   In deploy settings, set **Healthcheck Path** to `/health`.
4. Add these service variables before deploying:
   - `OPENAI_API_KEY`: a project key created in the OpenAI platform. This stays on
     Railway and is never entered into the phone app or committed to Git.
   - `FAMILIAR_ACCESS_TOKEN`: a separate random token shared only with your phone.
     Generate one locally: `python -c "import secrets; print(secrets.token_urlsafe(32))"`.
   - `OPENAI_MODEL`: optional model name, for example `gpt-6-luna` or `gpt-6.1-sol`. If omitted or
     blank, the service uses `gpt-4.1-mini-2025-04-14`. Change this variable and
     deploy the Railway changes to switch models; no Android update is needed.
5. Deploy. The Dockerfile starts the server on Railway's `PORT`; no start override,
   volume, database, or other service is needed. Keep one replica.
6. In **Settings → Networking**, generate a public domain. Check
   `https://YOUR-DOMAIN/health`; it should return `{"status":"ok"}`.
7. Update the Android app. In **Show developer details → Voice service setup**,
   enter `https://YOUR-DOMAIN` and the `FAMILIAR_ACCESS_TOKEN`, then save.
   Return to the creature and tap **Listen**.

Railway documentation: [monorepo setup](https://docs.railway.com/guides/deploying-a-monorepo),
[variables](https://docs.railway.com/variables),
[Dockerfiles](https://docs.railway.com/builds/dockerfiles).

## Behavior and limits

- Model selected with `OPENAI_MODEL`, no tools. v0.2b Listen requests select from
  [17 reviewed materials](discoveries.py): 6 facts, 6 original gentle jokes, and
  5 playful observation seeds. Facts and jokes are inserted verbatim; the model
  supplies a brief personality reaction rather than rewriting their core. Facts
  carry `sourceTitle` and `sourceUrl` for a link displayed by the Android app.
- New discoveries aim for 2–4 sentences, with a combined limit of 80 words and
  700 UTF-16 code units. They normally use a 400-output-token provider budget.
  Older APK requests without `discoveryVersion: 1` retain their compatible short
  format: normally 160 tokens, one or two sentences, at most 35 words / 280
  characters. Empty, incomplete, refused, or oversized responses are rejected.
- `gpt-6-luna` and its dated snapshots use `reasoning.effort: "none"` so reasoning
  does not consume the short output budget. `gpt-6.1-sol` uses low reasoning and a
  2,048-token cap shared by reasoning and visible output. Visible responses still
  follow the new or legacy limits above. This is a bounded starting budget,
  not a guarantee that every response finishes; incomplete responses are rejected.
  Other models use their API defaults;
  choose a Responses-compatible text model available to your OpenAI project.
- Requests use `store: false`; this is not a promise of zero provider retention.
  See [OpenAI data controls](https://developers.openai.com/api/docs/guides/your-data).
- Energy, stimulation, mode, curiosity, derived behavior, coarse/local time,
  battery percentage, and charging status form the basic model context. No identity
  timestamps, poke history, notifications, messages, or screen contents are sent.
- The phone keeps the last eight displayed material IDs in private preferences
  and sends them as `recentDiscoveryIds`. The backend excludes these materials
  from selection and returns a new `discoveryId`. History IDs are used by the
  backend, not forwarded to the model. Thought text and raw activity histories
  are not stored by this service.
- Optional app observations are off by default. The phone requires its local
  **Include selected apps** toggle, a chooser selection, and Android **Usage
  Access**. Up to eight apps may be chosen; at most five observed app names and
  rounded approximate foreground minutes from the past 60 minutes are sent in
  `environment.appUsage`. Raw events and package identifiers remain on the phone.
  No app contents, typing, web pages, intentions, or longer-term habits are known.
  The observations can influence a playful response without guilt or productivity
  advice. Without this optional summary, ordinary Listen still works.
- The prompt asks for consistent, gentle interpretation with no guilt or invented
  observations. Generated language can still be wrong; the model never owns reality.
- Thoughts are transient across app restarts but remain visible on resume and
  when returning from a source link. Poking, a new Listen action, or settings
  voice setting changes clear the displayed thought. Slow responses are discarded if state or
  settings changed while waiting; network work never holds the simulation mutex.
- One in-flight provider request; five-second cooldown; 100 attempts per UTC day
  per process. Failures also count. There are no automatic retries or background calls.
  Counters reset on restart/redeployment and are not a billing hard cap. Use provider
  and Railway spending controls as well. This is for one owner, not a public app.
- HTTPS is supplied by Railway. The phone refuses plaintext URLs and redirects.
  The personal access token is stored in private app preferences, excluded from
  Android backup. Rotate it on Railway and update the phone if it is exposed.
- OpenAI API calls and Railway hosting are billed separately from ChatGPT Plus.

## Test

From the repository root, with Python 3.13+:

```powershell
python -m unittest discover -s backend -v
```

Tests use fake provider responses, make no paid requests, and cover validation,
authentication, cooldown/day limits, discovery selection, source metadata,
legacy compatibility, output filtering, and error isolation.
Android instrumented voice tests cover state preservation, concurrent pokes,
discarding stale responses, and continued operation after a voice failure.

The prior Railway voice path was verified with a paid response on the owner's
phone. All 28 v0.2b backend tests pass, along with 46 JVM tests and 23 Android
instrumented tests. APK assembly and lint pass.
New catalogue responses and optional app observations have not yet been checked
with a live provider call.

## Local backend development

Set the two environment variables locally and run `python backend/server.py`.
This listens on port 8080 by default and exposes `/health` and `/think`. It is only
for backend development; the phone uses the deployed HTTPS URL. No PC needs to
stay running after deployment. The standard-library server is intentionally a
small authenticated personal gateway behind Railway's TLS proxy, not a general
multi-user production server.
