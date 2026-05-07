# grok-ecw-middleware

FastAPI middleware that sits between Grok (xAI) and eClinicalWorks (eCW).
It turns a raw patient-intake payload (free-text transcript, dictation,
or loosely-structured JSON) into a validated `PatientRecord` and writes
that record into eCW, while optionally forwarding a copy to a
configurable output connector (S3, Google Drive, or a generic webhook).

```
raw intake  ──►  Grok (xAI)  ──►  PatientRecord  ──►  eCW write
                                         │
                                         └──►  output connector
                                              (S3 / Drive / webhook)
```

## Design Principles

- **LLM is hot-swappable.** `llm/base.py` defines `BaseLLMClient`; the
  Grok implementation lives in `llm/grok.py`. To use OpenAI, Anthropic,
  Ollama, etc., write a new subclass and change one line in `config.py`.
- **ECW layer is stubbed.** Every method in `ecw/client.py` raises
  `NotImplementedError` with a docstring describing what the real
  implementation will do. The Pydantic models in `ecw/models.py` are
  fully defined so the rest of the system can already ship.
- **Output connectors are hot-swappable** the same way as the LLM.
  Set `OUTPUT_CONNECTOR` in `.env` to `generic_server`, `s3`, or
  `google_drive`.
- **Auth-aware retries.** `ECWClient._with_auth_retry()` wraps every
  API call: on 401/403 it calls `refresh_tokens()` and retries once.
- **AWS-ready.** No hardcoded paths. Every config value comes from an
  env var. The provided `Dockerfile` runs with `uvicorn` on port 8080
  and is ready to be shipped to ECS/Fargate, App Runner, or Lambda
  (via an adapter like Mangum).

## Project Layout

```
grok-ecw-middleware/
├── main.py                    # FastAPI app + /fill-patient-record
├── config.py                  # env-driven configuration + factories
├── requirements.txt
├── .env.example
├── Dockerfile
├── README.md
├── llm/
│   ├── __init__.py
│   ├── base.py                # BaseLLMClient abstract class
│   └── grok.py                # Grok implementation (openai SDK → xAI)
├── ecw/
│   ├── __init__.py
│   ├── base.py                # BaseECWClient abstract class
│   ├── client.py              # Stubbed eCW client (raises NotImplementedError)
│   └── models.py              # Pydantic models: PatientRecord, AppointmentRequest, etc.
├── connectors/
│   ├── __init__.py
│   ├── base.py                # BaseOutputConnector abstract class
│   ├── generic_server.py      # Stub: POST to an HTTP URL
│   ├── google_drive.py        # Stub: upload JSON to a Drive folder
│   └── s3.py                  # Stub: put_object to an S3 bucket
└── prompts/
    └── extraction.txt         # System prompt driving PatientRecord extraction
```

## Quick Start — Local Development (macOS)

### 1. Create and activate a virtual environment

```bash
cd grok-ecw-middleware
python3 -m venv .venv
source .venv/bin/activate
```

### 2. Install dependencies

```bash
pip install --upgrade pip
pip install -r requirements.txt
```

### 3. Configure environment variables

```bash
cp .env.example .env
# open .env in your editor and fill in XAI_API_KEY at minimum
```

The service boots even when required env vars are missing — it will log
warnings at startup and fail the individual request when the missing
value is actually needed. This keeps `/health` available for debugging.

### 4. Run the server

```bash
uvicorn main:app --reload --port 8080
```

### 5. Smoke-test

```bash
# Liveness
curl http://localhost:8080/health

# Extract a patient record end-to-end (ECW write is a stub,
# so the response will include "status": "not_implemented" for that step)
curl -X POST http://localhost:8080/fill-patient-record \
  -H "Content-Type: application/json" \
  -d '{
    "raw_text": "Patient Jane Smith, DOB 1985-07-12, presents with a 3-day history of dry cough and low-grade fever. No known drug allergies. Currently taking Lisinopril 10 mg daily for hypertension. Requests a follow-up visit in two weeks.",
    "source": "curl-test"
  }'
```

You should see a JSON response whose `extracted_record.demographics.first_name`
is "Jane" and whose `ecw_summary.status` is `"not_implemented"`.

## API

### `GET /health`

No auth. Returns the service name, version, and which connector / LLM
model are currently active. Useful as an ECS / K8s liveness probe.

### `POST /fill-patient-record`

Body:

```json
{
  "raw_text": "string — free text patient intake (optional)",
  "structured": { "...": "pre-structured record (optional; skips LLM)" },
  "patient_id": "string — existing eCW patient id (optional)",
  "source": "kiosk | extension | api | ... (optional, default 'api')"
}
```

Exactly one of `raw_text` or `structured` must be provided.

Response:

```json
{
  "request_id": "hex12",
  "patient_id": "string | null",
  "extracted_record": { "...validated PatientRecord..." },
  "ecw_summary": { "status": "ok | not_implemented | ...", "...": "..." },
  "connector_status": "sent | skipped | failed",
  "connector_error": "string | null",
  "elapsed_ms": 1234
}
```

## Swapping the LLM provider

1. Implement a new subclass of `llm.base.BaseLLMClient` in, say,
   `llm/anthropic.py`.
2. In `config.get_llm_client()`, import and instantiate your class
   instead of `GrokLLMClient`.
3. Restart the service. The rest of the code is unchanged.

## Swapping the output connector

Change `OUTPUT_CONNECTOR` in `.env` to one of:
- `generic_server` (default) — POSTs JSON to `GENERIC_SERVER_URL`.
- `google_drive` — uploads JSON files to `GOOGLE_DRIVE_FOLDER_ID`.
- `s3` — `put_object` into `S3_BUCKET` under `S3_PREFIX`.

All three are currently stubs; implement them before production use.

## Running with Docker

```bash
docker build -t grok-ecw-middleware .

docker run --rm -p 8080:8080 --env-file .env grok-ecw-middleware
```

The container runs as a non-root user, exposes port 8080, and ships
with a `HEALTHCHECK` that hits `/health`.

## AWS Deployment

The container is stateless and AWS-ready. Recommended path:

1. Push the image to ECR.
2. Run it on ECS/Fargate behind an ALB, OR on App Runner, OR wrap in
   Mangum for Lambda.
3. Inject env vars from AWS Secrets Manager (`XAI_API_KEY`,
   `ECW_PASSWORD`, etc.) via the task definition / App Runner config.
4. If using `OUTPUT_CONNECTOR=s3`, grant the task role
   `s3:PutObject` on `arn:aws:s3:::${S3_BUCKET}/${S3_PREFIX}*`.

No infrastructure-as-code is included in this folder — deploy with
whatever tooling the broader MedPull stack already uses.

## Safety Notes

- The extraction prompt (`prompts/extraction.txt`) explicitly forbids
  the model from hallucinating clinical data. If a field cannot be
  confidently determined, the model is instructed to emit `null`.
- The middleware NEVER inserts values into eCW that the model said it
  was unsure about — those fields arrive as `null` and the stubbed
  client will skip them.
- The service does not log raw PHI at `INFO` level; intake text is
  summarized (length, source) in logs. Keep `LOG_LEVEL=INFO` or higher
  in production.

## Status

| Component                | Status                                        |
|--------------------------|-----------------------------------------------|
| FastAPI orchestration    | Implemented                                   |
| LLM layer — base         | Implemented                                   |
| LLM layer — Grok         | Implemented                                   |
| ECW models               | Implemented                                   |
| ECW client               | **Stubbed** (raises `NotImplementedError`)    |
| Connector — generic HTTP | **Stubbed**                                   |
| Connector — Google Drive | **Stubbed**                                   |
| Connector — S3           | **Stubbed**                                   |
| Extraction prompt        | Implemented                                   |
| Dockerfile               | Implemented                                   |
