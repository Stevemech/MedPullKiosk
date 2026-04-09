# MedPull — AI-Powered Clinical Form Filling

MedPull automates eClinicalWorks form filling by extracting structured data from clinical transcripts using LLMs. A patient check-in kiosk submits transcripts to a central API server, and a Chrome extension lets clinicians review AI decisions and fill forms with one click — no auto-submit, ever.

## Architecture

```
┌───────────────┐         POST /forms         ┌──────────────────────┐
│  Android       │ ───────────────────────────► │                      │
│  Kiosk App     │                              │  FastAPI Server      │
│  (MedPullKiosk)│                              │  (AWS ECS Fargate)   │
└───────────────┘                              │                      │
                                               │  ┌────────────────┐  │
┌───────────────┐     GET/POST /forms/*        │  │  LiteLLM/Grok  │  │
│  Chrome        │ ◄──────────────────────────► │  │  (llm_router)  │  │
│  Extension     │                              │  └────────────────┘  │
│                │                              │                      │
│  content.js ───┼──── fills eCW DOM           │  ┌────────────────┐  │
│  (review +     │                              │  │  DynamoDB      │  │
│   confirm)     │                              │  │  (form_store)  │  │
└───────────────┘                              └──┴────────────────┴──┘
```

**Three components:**

| Component | Location | Purpose |
|-----------|----------|---------|
| **Android Kiosk App** | `MedPullKiosk/` | Patient check-in — captures transcripts, POSTs to server |
| **FastAPI Server** | `server/` | Central API hub — LLM processing, form queue (DynamoDB) |
| **Chrome Extension** | `chrome-extension/` | Clinician UI — reviews decisions, fills eCW forms via DOM |

## Repository Structure

```
MedPullKiosk/
├── MedPullKiosk/              # Android kiosk app (Kotlin/Gradle)
├── chrome-extension/          # Chrome extension (Manifest V3)
│   ├── manifest.json
│   ├── popup.html / popup.js  # Extension UI (queue + manual modes)
│   ├── content.js             # eCW DOM: schema extraction + field filling
│   ├── background.js          # Service worker: API proxy
│   └── styles.css
├── server/                    # FastAPI server (deployed to AWS)
│   ├── main.py                # API endpoints
│   ├── llm_router.py          # LiteLLM + Grok structured extraction
│   ├── form_store.py          # DynamoDB form queue
│   ├── form_schema.py         # Pydantic models for LLM output
│   ├── input_adapter.py       # Pluggable input adapters
│   ├── auth.py                # API key authentication
│   ├── Dockerfile
│   ├── docker-compose.yml     # Local dev (server + DynamoDB Local)
│   ├── requirements.txt
│   ├── .env.example
│   └── deploy/
│       ├── cloudformation.yaml
│       └── deploy.sh
└── README.md
```

## Quick Start — Local Development

### 1. Start the server with Docker Compose

```bash
cd server
cp .env.example .env
# Edit .env — set XAI_API_KEY and your clinic API key

docker-compose up --build
```

This starts the FastAPI server on `http://localhost:8000` and a local DynamoDB instance on port 8100.

### 2. Create the local DynamoDB table

```bash
aws dynamodb create-table \
  --table-name medpull-forms \
  --attribute-definitions \
    AttributeName=clinic_id,AttributeType=S \
    AttributeName=form_id,AttributeType=S \
  --key-schema \
    AttributeName=clinic_id,KeyType=HASH \
    AttributeName=form_id,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST \
  --endpoint-url http://localhost:8100
```

### 3. Verify the server is running

```bash
curl http://localhost:8000/health
# → {"status":"ok"}
```

### 4. Load the Chrome extension

1. Open `chrome://extensions/`
2. Enable **Developer Mode**
3. Click **Load unpacked** → select the `chrome-extension/` folder
4. Open the extension and go to **Settings** (bottom of popup)
5. Set **Server URL** to `http://localhost:8000`
6. Set **API Key** to the key you configured in `.env`

## AWS Deployment

### Prerequisites

- AWS CLI configured with appropriate credentials
- Docker installed
- An ACM certificate for HTTPS (or use a self-signed cert for testing)
- A VPC with at least 2 public subnets

### 1. Deploy infrastructure with CloudFormation

```bash
aws cloudformation deploy \
  --template-file server/deploy/cloudformation.yaml \
  --stack-name medpull \
  --parameter-overrides \
    VpcId=vpc-xxxxx \
    SubnetIds=subnet-aaa,subnet-bbb \
    CertificateArn=arn:aws:acm:us-east-1:123456789012:certificate/xxxxx \
    ImageUri=123456789012.dkr.ecr.us-east-1.amazonaws.com/medpull-server:latest \
  --capabilities CAPABILITY_NAMED_IAM
```

### 2. Update secrets in AWS Secrets Manager

After the stack creates, update the `medpull/server-config` secret with your actual values:
- `XAI_API_KEY` — your Grok API key
- `MEDPULL_API_KEYS` — comma-separated list of clinic API keys
- `MEDPULL_KEY_MAP` — key:clinic_id pairs (e.g., `abc123:clinic_001`)

### 3. Build and deploy the server

```bash
cd server/deploy
./deploy.sh
```

The script builds the Docker image, pushes to ECR, updates the ECS service, waits for stabilization, and prints the ALB URL.

### 4. Update the Chrome extension

In `chrome-extension/manifest.json`, replace `YOUR_ALB_DOMAIN_HERE` with your ALB domain. Reload the extension and set the server URL in settings.

## API Endpoints

All endpoints except `/health` require the `X-API-Key` header. The key determines the clinic context automatically.

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/health` | Health check (no auth) |
| `POST` | `/forms` | Submit a new form (transcript + optional schema) |
| `GET` | `/forms` | List forms by status (default: `pending,ready`) |
| `GET` | `/forms/{form_id}` | Get a single form with decisions |
| `POST` | `/forms/{form_id}/process` | Trigger LLM processing on a form |
| `POST` | `/forms/{form_id}/claim` | Claim a form before filling eCW |
| `POST` | `/forms/{form_id}/complete` | Mark a form as done |
| `POST` | `/process` | Legacy: direct transcript processing |

### Android Kiosk Integration

The kiosk app submits forms via:

```bash
curl -X POST https://YOUR_SERVER/forms \
  -H "Content-Type: application/json" \
  -H "X-API-Key: YOUR_CLINIC_KEY" \
  -d '{
    "patient_id": "PAT-12345",
    "transcript": "Patient presents with...",
    "source": "kiosk"
  }'
```

The form enters the queue as `pending`. The Chrome extension polls for new forms and the clinician can trigger LLM processing, review decisions, and fill the eCW form.

## Generating API Keys for a New Clinic

1. Generate a secure random key (e.g., `openssl rand -hex 32`)
2. Add the key to the `MEDPULL_API_KEYS` env var (comma-separated)
3. Add the mapping to `MEDPULL_KEY_MAP` (e.g., `new_key:clinic_002`)
4. Update the Secrets Manager secret in AWS (or `.env` for local dev)
5. Restart the ECS service (or the local server)

## Swapping the LLM Provider

The server uses LiteLLM, so any supported provider works with a config change:

```env
# Grok (default)
MODEL_PRIMARY=xai/grok-3-mini
MODEL_FALLBACK=xai/grok-3

# OpenAI
MODEL_PRIMARY=gpt-4o
MODEL_FALLBACK=gpt-4o
OPENAI_API_KEY=sk-xxx

# Anthropic
MODEL_PRIMARY=anthropic/claude-sonnet-4-20250514
MODEL_FALLBACK=anthropic/claude-sonnet-4-20250514
ANTHROPIC_API_KEY=sk-ant-xxx

# Local Ollama
MODEL_PRIMARY=ollama/qwen2.5:14b
MODEL_FALLBACK=ollama/qwen2.5:72b
OLLAMA_API_BASE=http://localhost:11434
```

## Safety Design

- **No auto-submit** — the extension fills fields but never clicks save/submit in eCW
- **Mandatory clinician review** — the confirm button only activates after decisions are shown
- **Optimistic locking** — `claim` uses a DynamoDB condition expression to prevent double-fills
- **No hallucinated values** — the LLM system prompt forbids fabricating clinical data
- **Confidence routing** — low-confidence decisions trigger re-processing with a more capable model
- **Flagged fields** — required fields missing from the transcript are highlighted red
- **TTL auto-cleanup** — form records expire after 30 days via DynamoDB TTL
