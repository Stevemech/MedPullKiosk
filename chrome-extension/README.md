# MedPull eClinicalWorks Chrome Extension

AI-powered clinical transcript processing and eClinicalWorks form filling. Uses LiteLLM + Grok to extract structured field decisions from clinical transcripts, with mandatory clinician review before any data touches the EHR.

## Architecture

```
┌─────────────────────┐      HTTP (localhost:8000)      ┌──────────────────────┐
│   Chrome Extension   │ ◄──────────────────────────────► │   FastAPI Server     │
│   (Manifest V3)      │                                  │                      │
│                      │                                  │  ┌────────────────┐  │
│  • Transcript input  │                                  │  │  LiteLLM/Grok  │  │
│  • Review panel      │                                  │  │  (llm_router)  │  │
│  • Confirm button    │                                  │  └────────────────┘  │
│                      │                                  │                      │
│  content.js ◄────────┼──── eClinicalWorks DOM ────────► │  ┌────────────────┐  │
│  (schema extract +   │                                  │  │  Playwright    │  │
│   field highlight)   │                                  │  │  (form filler) │  │
└─────────────────────┘                                  └──┴────────────────┴──┘
                                                                    │
                                                                    ▼
                                                           Chrome via CDP
                                                         (remote debugging)
```

**Two components:**
1. **Chrome Extension** — clinician-facing UI for transcript input, decision review, and confirmation
2. **Local Python Server** — FastAPI app that calls LiteLLM + Grok for extraction and Playwright for form filling

## Prerequisites

- Python 3.10+
- Google Chrome or Chromium
- A Grok API key from [xAI](https://console.x.ai/)

## Installation

### 1. Install Python dependencies

```bash
cd chrome-extension/server
pip install -r requirements.txt
playwright install chromium
```

### 2. Configure environment variables

```bash
cp .env.example .env
# Edit .env and add your Grok API key:
#   XAI_API_KEY=xai-xxxxxxxxxxxxxxxxxxxx
```

### 3. Start Chrome with remote debugging

This allows Playwright to connect to your existing Chrome session where eClinicalWorks is already open and you're logged in.

**macOS:**
```bash
/Applications/Google\ Chrome.app/Contents/MacOS/Google\ Chrome \
  --remote-debugging-port=9222 \
  --user-data-dir=/tmp/chrome-debug
```

**Linux:**
```bash
google-chrome --remote-debugging-port=9222 --user-data-dir=/tmp/chrome-debug
```

**Windows:**
```cmd
"C:\Program Files\Google\Chrome\Application\chrome.exe" --remote-debugging-port=9222 --user-data-dir=%TEMP%\chrome-debug
```

> **Tip:** Log into eClinicalWorks in this Chrome instance before starting the server.

### 4. Start the local server

```bash
cd chrome-extension/server
uvicorn main:app --reload --port 8000
```

Verify it's running:
```bash
curl http://localhost:8000/health
# → {"status":"ok"}
```

### 5. Load the Chrome extension

1. Open `chrome://extensions/` in the debug Chrome instance
2. Enable **Developer Mode** (toggle in the top-right)
3. Click **Load unpacked**
4. Select the `chrome-extension/` folder (this folder)
5. The MedPull Assistant icon should appear in your toolbar

## Usage

1. Navigate to a patient's form in eClinicalWorks
2. Click the MedPull Assistant extension icon
3. Paste or type the clinical transcript (or upload a text file)
4. Click **Process & Fill** — the server extracts field decisions via Grok
5. Review every field decision in the panel:
   - 🟢 **Fill** — value extracted from transcript (with confidence score)
   - ⚪ **Skip** — field is inapplicable (with reasoning)
   - 🔴 **Flag** — required field missing from transcript (needs manual input)
6. After reviewing all decisions, click **Confirm & Submit to eClinicalWorks**
7. Playwright fills the form in your Chrome session — watch it happen in real time
8. **Always verify the filled form in eClinicalWorks before saving the encounter**

> ⚠️ **No auto-submit**: The extension never submits forms automatically. You must always click the final save/submit button in eClinicalWorks yourself.

## Swapping the LLM Provider

The system uses LiteLLM, so you can swap to any supported provider with a one-line change.

### Use a local Ollama model

```env
MODEL_PRIMARY=ollama/qwen2.5:14b
MODEL_FALLBACK=ollama/qwen2.5:72b
OLLAMA_API_BASE=http://localhost:11434
```

### Use OpenAI

```env
MODEL_PRIMARY=gpt-4o
MODEL_FALLBACK=gpt-4o
OPENAI_API_KEY=sk-xxxxxxxxxxxxxxxxxxxx
```

### Use Anthropic

```env
MODEL_PRIMARY=anthropic/claude-sonnet-4-20250514
MODEL_FALLBACK=anthropic/claude-sonnet-4-20250514
ANTHROPIC_API_KEY=sk-ant-xxxxxxxxxxxxxxxxxxxx
```

## Folder Structure

```
chrome-extension/
├── manifest.json           # Manifest V3 extension config
├── popup.html              # Extension popup / side panel UI
├── popup.js                # Popup logic: input, review, confirm
├── content.js              # eCW page: schema extraction + field highlighting
├── background.js           # Service worker: proxies requests to local server
├── styles.css              # Extension UI styles
├── server/
│   ├── main.py             # FastAPI app with /process, /execute, /health
│   ├── llm_router.py       # LiteLLM + Grok integration with confidence routing
│   ├── playwright_agent.py # Playwright automation for eClinicalWorks
│   ├── form_schema.py      # Pydantic models for structured LLM output
│   ├── input_adapter.py    # Pluggable input adapters (text, audio, PDF, HL7)
│   ├── requirements.txt    # Python dependencies
│   └── .env.example        # Environment variable template
└── README.md               # This file
```

## Safety Design

- **No auto-submit** — Playwright fills fields but never clicks save/submit
- **Mandatory clinician review** — the Confirm button only activates after the review panel is shown
- **Error isolation** — every Playwright action is individually wrapped in error handling
- **No hallucinated values** — the LLM system prompt explicitly forbids fabricating clinical data
- **Confidence routing** — low-confidence decisions trigger automatic re-processing with a more capable model
- **Flagged fields** — required fields without transcript evidence are highlighted red for manual input

## Extending the Input Adapters

The `input_adapter.py` file defines a pluggable interface for transcript sources:

| Adapter | Status | Description |
|---------|--------|-------------|
| `RawTextAdapter` | ✅ Implemented | Plain text input (default) |
| `AudioFileAdapter` | 🔲 Stub | Audio dictation via Whisper |
| `PDFAdapter` | 🔲 Stub | Scanned clinical notes via OCR |
| `HL7Adapter` | 🔲 Stub | HL7 message parsing |

To implement a new adapter, subclass `InputAdapter` and implement `get_transcript() -> str`.

## License

Part of the MedPullKiosk project.
