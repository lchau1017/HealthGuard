# HealthGuard extraction proxy

Ktor server that holds the OpenRouter API key so it never ships in the app.
One endpoint:

| Route | Behaviour |
|---|---|
| `POST /extract` with `{"imageJpegBase64": "..."}` | Forwards the image to the vision model (default `qwen/qwen2.5-vl-72b-instruct`) with a forced JSON schema (structured outputs) and returns the extraction JSON verbatim |
| bad/missing body | `400 {"error":"imageJpegBase64 required"}` |
| model provider failure | `502 {"error":"upstream"}` |
| provider returned no content | `502 {"error":"no content"}` |
| anything else | `404` |

## Run locally

```
OPENROUTER_API_KEY=<your key> ./gradlew :backend:server:run
```

Listens on port 8787 (override with `PORT`); the Android debug build points at
`http://10.0.2.2:8787`, which reaches this server from the emulator. Override
the model with `MODEL_ID`.

## Deploy

Any JVM host works. Suggested: containerise (`./gradlew :backend:server:installDist`
+ a JRE base image) and deploy to Google Cloud Run with `OPENROUTER_API_KEY`
set as a secret-backed environment variable.

## Privacy

Images pass straight through to the model provider for extraction — they are
never persisted or logged here, and upstream error bodies are never echoed to
clients.
