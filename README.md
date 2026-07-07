# MedGuard

Photograph a medication package, and MedGuard reads the label with a vision
LLM, extracts structured data (name, dosage, frequency, ingredients), and adds
it to your medication list — where you can press ▶ to start taking it.

> **MedGuard is an informational and reminder tool, not medical advice.**
> It never makes medical judgements. Always consult your doctor or pharmacist.

## How it works

```
┌──────────────┐  photo (base64)  ┌────────────────┐  forced JSON schema  ┌────────────┐
│  Android app │ ───────────────► │  Ktor backend  │ ───────────────────► │ OpenRouter │
│  (Compose)   │ ◄─────────────── │  (proxy, holds │ ◄─────────────────── │  Qwen VL   │
└──────────────┘  extraction JSON │   the API key) │                      └────────────┘
```

- The **API key never ships in the app** — only the backend talks to the model provider.
- The model only *transcribes* the label; every low-confidence field is
  flagged and must be confirmed or corrected by the user before saving
  (human-in-the-loop).
- Data is stored locally on the device only.

| Module | What it is |
|---|---|
| `app/` | Android app — Jetpack Compose, Koin DI, single-screen UX |
| `shared/` | Kotlin Multiplatform library — extraction parsing, domain logic (dose scheduling), SQLDelight persistence. Android + JVM targets today, structured to add iOS |
| `backend/server/` | Ktor proxy server — `POST /extract` forwards a label photo to the vision model with a strict JSON schema |

## Prerequisites

- **JDK 21** (the build pins the Gradle daemon toolchain to 21; bytecode targets 17)
- **Android Studio** (latest stable) with an emulator image or a physical Android device (Android 7.0+, API 24)
- An **OpenRouter API key** — sign up at [openrouter.ai](https://openrouter.ai), create a key under *Keys*, and add a small amount of credit. A label scan with the default model (`qwen/qwen2.5-vl-72b-instruct`) costs a fraction of a cent. Setting a monthly spend limit on the key is recommended.

## 1. Start the backend server

The app cannot extract anything without the backend running.

Create `backend/server/.env` (git-ignored) containing your key:

```
OPENROUTER_API_KEY=sk-or-v1-...
```

The `run` task loads that file automatically, so starting the server is just:

```bash
./gradlew :backend:server:run
```

**From Android Studio instead of a terminal:** open the Gradle tool window
(the elephant icon, right edge) → `MedGuard → backend → server → Tasks →
application → run` and double-click it. Android Studio adds it to the run
configuration dropdown next to ▶, so from then on you can start the backend
with the Run button and stop it with the red ■ — no terminal needed. (A
ready-made "backend server" run configuration may already appear in the
dropdown after a project reload.)

An environment variable still wins over `.env` if you prefer:
`OPENROUTER_API_KEY=sk-or-v1-... ./gradlew :backend:server:run`

Success looks like:

```
INFO io.ktor.server.Application -- Application started ...
```

The server listens on **port 8787** and keeps running until you press `Ctrl-C`.
Optional environment variables: `PORT` (default 8787), `MODEL_ID` (default
`qwen/qwen2.5-vl-72b-instruct` — any vision-capable OpenRouter model id works).

**"Address already in use"?** A previous server instance is still holding the
port. Free it and start again:

```bash
lsof -ti :8787 | xargs kill
```

Quick health check (404 is the expected answer for the root path):

```bash
curl -i http://localhost:8787/
```

## 2. Run the app

Open the project in Android Studio, let Gradle sync, and pick **one** of the
three setups below depending on where the app will run. The app finds the
backend through one line in `local.properties` (a git-ignored file in the
repository root that Android Studio creates automatically).

### Option A — Emulator (zero configuration)

Nothing to configure. Inside an emulator, the special address `10.0.2.2`
means "the host machine", and that is the debug build's default.

1. Start the backend (step 1).
2. Select an emulator and press **Run ▶**.

### Option B — Real device over USB

The phone reaches your computer through an adb port tunnel — works even
without Wi-Fi.

1. Enable *Developer options* → *USB debugging* on the phone and plug it in.
2. Add this line to `local.properties`:

   ```properties
   medguard.proxyBaseUrl=http://127.0.0.1:8787
   ```

3. Create the tunnel (re-run this any time the phone is re-plugged, rebooted,
   or adb restarts — it drops silently):

   ```bash
   adb reverse tcp:8787 tcp:8787
   ```

4. Start the backend (step 1), select the device, press **Run ▶**.

### Option C — Real device over Wi-Fi

The phone talks to your computer directly; both must be on the **same
Wi-Fi network**.

1. Find your computer's LAN IP:
   - macOS: `ipconfig getifaddr en0`
   - Linux: `hostname -I`
   - Windows: `ipconfig` (IPv4 address)
2. Add it to `local.properties`:

   ```properties
   medguard.proxyBaseUrl=http://192.168.1.42:8787   # use YOUR IP
   ```

3. Start the backend (step 1), select the device, press **Run ▶**.
4. If your OS firewall prompts about incoming connections for Java, allow it.

Note: routers reassign IPs from time to time — if extraction stops working
after a few days, re-check the IP and update `local.properties`.

> Debug builds allow plain-HTTP traffic so these local setups work; release
> builds block cleartext entirely and expect an HTTPS backend URL.

## 3. Try the flow

1. **Import medication** → *Take photo* or *Choose from gallery* → aim at a
   medication box or label.
2. Watch *Reading label…* — the photo goes through the backend to the vision
   model.
3. The review dialog shows the extracted fields. Anything the model was not
   confident about is highlighted and must be confirmed or corrected before
   **Accept** unlocks. Add an optional label (e.g. *hay fever*) if you like.
4. Accept — the medication appears in the home list.
5. Press **▶** on a row to mark it as actively taking; it moves to the top
   with a *Taking* badge. Press stop to pause it, or the bin icon to delete.

No backend running? The app degrades gracefully to
*"Service unavailable — check connection"* with a Retry button.

## Running the tests

```bash
./gradlew :shared:jvmTest            # parser, repository, dose calculator
./gradlew :app:testDebugUnitTest     # view models (against a real in-memory DB)
./gradlew :backend:server:test       # proxy contract tests (stubbed upstream)
```

CI (GitHub Actions) runs all of the above plus lint and an APK assembly on
every push.

## Troubleshooting

| Symptom | Likely cause → fix |
|---|---|
| *Service unavailable — check connection* | Backend not running → step 1. USB: tunnel dropped → `adb reverse tcp:8787 tcp:8787`. Wi-Fi: phone on mobile data or wrong network → join the same Wi-Fi; IP changed → update `local.properties` and rebuild |
| `Address already in use` when starting the server | `lsof -ti :8787 \| xargs kill`, then start again |
| Extraction returns *Couldn't read the label* | Blurry/dark photo → retake with the label flat and filling the frame |
| Server logs `502` / app can't extract despite server running | OpenRouter key invalid or out of credit → check [openrouter.ai/activity](https://openrouter.ai/activity) |
| Every field is flagged for review | Working as designed on hard labels — confirm or correct the fields; the app never trusts low-confidence output silently |
| Gradle sync/build errors about JVM versions | Use JDK 21 (Android Studio: *Settings → Build Tools → Gradle → Gradle JDK*) |

## Privacy & data handling

- Health data stays **on the device**; there is no account and no cloud sync.
- Label photos are sent to the backend and forwarded to the model provider
  for extraction only — the backend never stores or logs them.
- The backend never echoes provider errors (or anything containing the API
  key) to clients.
- Delete removes the medication and its entire dose history (right to
  erasure by design).
