<p align="center">
  <img src="files/3ayn-logo-transparent.png" width="220" alt="3ayn عين logo">
</p>

<h1 align="center">3ayn عين</h1>

<p align="center">
  <b>A real-time AI social awareness assistant for blind and low-vision users — Arabic-first.</b><br>
  Seeing AI tells you what's around you. <b>3ayn tells you what's happening around you.</b>
</p>

<p align="center">
  Built for the Amazon Industry Program 2026 · AWS Serverless · Java 21 · MediaPipe
</p>

---

## What it does

Existing accessibility apps are reactive and object-focused: the user taps, the app describes objects. 3ayn is **proactive and socially focused** — it watches continuously and narrates social events out loud, in Arabic, the moment they happen:

| Event | Narration |
|---|---|
| Someone approaches | شخص يقترب منك |
| Someone extends a hand | شخص يمد يده نحوك |
| Someone appears in front of you | شخص أمامك |
| Crowd / quiet | المكان مزدحم / المكان هادئ |
| A known person is recognized | أمامك ماريتا، أختك |

Plus three on-demand modes: **Ask** (describe my surroundings), **Read** (read the text in front of me), and **Find** (where is my bottle? → *"إلى يسارك"*).


## The core architecture decision

A cloud round trip takes 1–3 seconds. *"Someone is extending a hand toward you"* narrated 3 seconds late is useless — so 3ayn splits by **latency requirement**:

```
 LATENCY-CRITICAL (continuous)          LATENCY-TOLERANT (user-initiated)
 ─────────────────────────────          ──────────────────────────────────
 MediaPipe pose estimation               Ask   →  Bedrock Nova 2 Lite
 + geometric heuristics                  Read  →  Textract
 runs IN THE BROWSER                     Find  →  Rekognition Labels
 zero network calls                      Who   →  Rekognition Faces
 instant narration                       Speak →  Polly (Arabic neural voice)
```

Real-time narration never waits for a network. Everything the user explicitly asks for runs on AWS, where managed AI earns its latency.

## AWS services used

**Bedrock (Nova 2 Lite)** scene description · **Textract** OCR · **Rekognition** object labels + face matching · **Polly** Arabic TTS · **Lambda** (7 functions, Java 21) · **API Gateway** · **DynamoDB** (users + face profiles) · **SAM** infrastructure-as-code · **Amplify** hosting

## Repository structure

```
3ayn/
├── files/
│   └── 3ayn-app.html          ← the entire frontend (single file)
├── 3ayn-backend/
│   ├── template.yaml          ← the whole AWS stack, declared
│   ├── samconfig.toml
│   └── ThreeAynFunction/      ← Java 21 handlers (7 endpoints)
└── docs/                      ← technical report, screenshots
```

## Run the frontend

```bash
cd files
python -m http.server 8000
# open http://localhost:8000/3ayn-app.html  (camera requires localhost or HTTPS)
```

Register with your name, then paste the backend URL in **Settings → Test connection**.

## Deploy the backend

```bash
cd 3ayn-backend
sam build
sam deploy --guided        # stack: threeayn, region: eu-west-2
```

Copy the `ApiBaseUrl` output into the app's Settings. Full endpoint contracts, smoke tests, and cost notes: [`3ayn-backend/README.md`](3ayn-backend/README.md).

## API

| Endpoint | Service | Returns |
|---|---|---|
| `POST /ask` | Bedrock Nova 2 Lite | Arabic/English scene description |
| `POST /read` | Textract | Text in frame, read aloud |
| `POST /find` | Rekognition Labels | Object + direction (left/right/front) |
| `POST /who` | Rekognition Faces | Name + relation of enrolled person |
| `POST /enroll` | Rekognition + DynamoDB | Caregiver enrolls a known face |
| `POST /user`, `GET /user/{id}` | DynamoDB | Wearer profile + spoken welcome |
| `POST /speak` | Polly | Base64 MP3, Arabic neural voice |

## Privacy & consent

Face recognition matches **only people deliberately enrolled by a caregiver**, with their knowledge. Strangers are never stored, fingerprinted, or clustered. No authentication by design — 3ayn is a single-wearer assistive device; a profile personalizes narration, it is not an account.

## Roadmap

Event history per wearer → caregiver dashboard (live log, alerts) · "someone is speaking to you" detection · voice-command language switching (Transcribe) · smart cane companion (ESP32 + ultrasonic) · smart glasses form factor.

## Team

Built by **Team 8** — Amazon Industry Program 2026 (IEEE / Women in Engineering, AUB).
