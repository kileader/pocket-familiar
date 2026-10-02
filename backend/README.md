# Pocket Familiar voice gateway

Small personal prototype using Python's standard library and OpenAI's Responses API.
The Android app owns the creature and SQLite database. This service receives an
observation and returns only `{ "text": "…" }`. It has no database or state commands.

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

- Model selected with `OPENAI_MODEL`, no tools, normally up to 160 output tokens,
  one or two sentences of at most 35 words / 280 characters. Provider responses
  that are empty, incomplete, refused, or too long are rejected.
- `gpt-6-luna` and its dated snapshots use `reasoning.effort: "none"` so reasoning
  does not consume the short output budget. `gpt-6.1-sol` uses low reasoning and a
  2,048-token cap shared by reasoning and visible output; the visible thought is
  still limited to 35 words / 280 characters. This is a bounded starting budget,
  not a guarantee that every response finishes; incomplete responses are rejected.
  Other models use their API defaults;
  choose a Responses-compatible text model available to your OpenAI project.
- Requests use `store: false`; this is not a promise of zero provider retention.
  See [OpenAI data controls](https://developers.openai.com/api/docs/guides/your-data).
- Only energy, stimulation, mode, curiosity, derived behavior, coarse/local time,
  battery percentage, and charging status leave the phone. No identity timestamps,
  interaction history, app use, notifications, messages, or screen contents.
- The prompt asks for consistent, gentle interpretation with no guilt or invented
  observations. Generated language can still be wrong; the model never owns reality.
- Thoughts are transient and cleared when the creature refreshes or is poked.
  Slow responses are discarded if state or settings changed while waiting.
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
authentication, cooldown/day limits, output filtering, and error isolation.
Android instrumented voice tests cover state preservation, concurrent pokes,
discarding stale responses, and continued operation after a voice failure.

## Local backend development

Set the two environment variables locally and run `python backend/server.py`.
This listens on port 8080 by default and exposes `/health` and `/think`. It is only
for backend development; the phone uses the deployed HTTPS URL. No PC needs to
stay running after deployment. The standard-library server is intentionally a
small authenticated personal gateway behind Railway's TLS proxy, not a general
multi-user production server.
