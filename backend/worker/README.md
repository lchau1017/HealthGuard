# MedGuard extraction proxy

Cloudflare Worker that sits between the MedGuard Android app and OpenRouter, so the
OpenRouter API key never ships inside the client. It forwards a medication-label photo
to a vision model (Qwen 2.5 VL by default) and returns the structured extraction
produced via OpenRouter structured outputs (a strict `json_schema` response format
named `report_extraction`).

## Endpoints

| Method | Path       | Body                              | Response |
| ------ | ---------- | --------------------------------- | -------- |
| POST   | `/extract` | `{"imageJpegBase64": "<base64>"}` | `200` extraction JSON |

Errors:

- Missing/invalid `imageJpegBase64` or unparseable JSON body: `400 {"error":"imageJpegBase64 required"}`
- Any other route or method: `404`
- OpenRouter unreachable or non-OK: `502 {"error":"upstream"}`
- Model answered without message content: `502 {"error":"no content"}`

## Local development

```sh
npm install
npm test
```

Create a `.dev.vars` file (gitignored, never commit it) with your key, then start the
dev server:

```
OPENROUTER_API_KEY=sk-or-...
```

```sh
npx wrangler dev
```

## Deploy

```sh
npx wrangler deploy
npx wrangler secret put OPENROUTER_API_KEY
```

`MODEL_ID` is an optional plain var (set it in `wrangler.toml` under `[vars]` or via the
dashboard); when unset the worker uses `qwen/qwen2.5-vl-72b-instruct`.

## Privacy

The image is forwarded to the vision model and nowhere else. The worker never persists,
logs, or echoes back image data; nothing about the request body is written to logs.
