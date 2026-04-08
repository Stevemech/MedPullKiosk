"""
main.py — FastAPI server for MedPull eClinicalWorks Assistant.

Provides three endpoints:
  POST /process  — accepts transcript + form schema, returns LLM field decisions
  POST /execute  — accepts confirmed decisions, runs Playwright to fill the form
  GET  /health   — simple health check

Run with: uvicorn main:app --reload --port 8000
"""

from __future__ import annotations

import logging
from typing import Any, Optional

from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from form_schema import FieldDecision, FormDecisions
from input_adapter import RawTextAdapter
from llm_router import process_transcript
from playwright_agent import execute_decisions

load_dotenv()

# ── Logging ───────────────────────────────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger(__name__)

# ── FastAPI App ───────────────────────────────────────────────────
app = FastAPI(
    title="MedPull eClinicalWorks Server",
    description="Local companion server for the MedPull Chrome extension.",
    version="1.0.0",
)

# ── CORS — allow the Chrome extension origin ─────────────────────
app.add_middleware(
    CORSMiddleware,
    allow_origins=[
        "chrome-extension://*",
        "http://localhost",
        "http://localhost:*",
        "http://127.0.0.1",
        "http://127.0.0.1:*",
    ],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ── Request / Response Models ─────────────────────────────────────


class ProcessRequest(BaseModel):
    """Request body for POST /process."""

    transcript: str = Field(
        ...,
        min_length=1,
        description="The clinical transcript text to process.",
    )
    form_schema: list[dict[str, Any]] = Field(
        default_factory=list,
        description="Array of form field descriptors extracted from eClinicalWorks.",
    )


class ExecuteRequest(BaseModel):
    """Request body for POST /execute."""

    decisions: list[FieldDecision] = Field(
        ...,
        description="Clinician-confirmed field decisions to execute.",
    )
    ecw_url: Optional[str] = Field(
        default=None,
        description="Optional eClinicalWorks URL to navigate to before filling.",
    )


class ExecuteResponse(BaseModel):
    """Response body for POST /execute."""

    success: bool
    errors: list[str] = Field(default_factory=list)
    screenshot_base64: str = ""


class HealthResponse(BaseModel):
    """Response body for GET /health."""

    status: str = "ok"


# ── Endpoints ─────────────────────────────────────────────────────


@app.get("/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    """Simple health check endpoint."""
    return HealthResponse(status="ok")


@app.post("/process", response_model=FormDecisions)
async def process_form(request: ProcessRequest) -> FormDecisions:
    """
    Process a clinical transcript against a form schema.

    Routes the raw text through the InputAdapter, then calls the LLM
    router to extract structured field decisions.
    """
    logger.info(
        "POST /process — transcript length: %d, form fields: %d",
        len(request.transcript),
        len(request.form_schema),
    )

    try:
        # Route through the input adapter (pluggable — currently raw text)
        adapter = RawTextAdapter(request.transcript)
        transcript = adapter.get_transcript()

        # Call the LLM router for structured extraction
        decisions = await process_transcript(transcript, request.form_schema)

        logger.info(
            "Processing complete — %d decisions, %d missing required",
            len(decisions.decisions),
            len(decisions.missing_required),
        )

        return decisions

    except ValueError as e:
        logger.error("Validation error in /process: %s", e)
        raise HTTPException(status_code=422, detail=str(e)) from e
    except Exception as e:
        logger.error("Unexpected error in /process: %s", e, exc_info=True)
        raise HTTPException(
            status_code=500,
            detail=f"Processing failed: {e}",
        ) from e


@app.post("/execute", response_model=ExecuteResponse)
async def execute_form(request: ExecuteRequest) -> ExecuteResponse:
    """
    Execute clinician-confirmed field decisions on eClinicalWorks.

    Calls the Playwright agent to fill fields in the browser. This
    endpoint should ONLY be called after the clinician has reviewed
    and confirmed all decisions in the extension UI.
    """
    logger.info(
        "POST /execute — %d decisions to execute",
        len(request.decisions),
    )

    try:
        result = await execute_decisions(
            decisions=request.decisions,
            ecw_url=request.ecw_url,
        )

        if result["success"]:
            logger.info("Execution completed successfully")
        else:
            logger.warning("Execution completed with errors: %s", result["errors"])

        return ExecuteResponse(
            success=result["success"],
            errors=result.get("errors", []),
            screenshot_base64=result.get("screenshot_base64", ""),
        )

    except Exception as e:
        logger.error("Unexpected error in /execute: %s", e, exc_info=True)
        raise HTTPException(
            status_code=500,
            detail=f"Execution failed: {e}",
        ) from e
