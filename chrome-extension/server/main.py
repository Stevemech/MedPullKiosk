"""
main.py — FastAPI server for MedPull eClinicalWorks Assistant.

Central hub deployed on AWS ECS that both the Android kiosk app and the
Chrome extension communicate with. Provides:
  - Form queue CRUD (POST/GET /forms, claim, complete)
  - LLM processing via /forms/{form_id}/process
  - Legacy /process endpoint for backward compatibility
  - Health check at GET /health

Run locally: uvicorn main:app --reload --port 8000
"""

from __future__ import annotations

import logging
from typing import Any, Optional

from dotenv import load_dotenv

load_dotenv(override=False)

from fastapi import FastAPI, HTTPException, Depends, Query
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from auth import verify_api_key
from form_schema import FieldDecision, FormDecisions
from form_store import (
    FormRecord,
    FormStatus,
    create_form,
    get_form,
    list_forms_by_status,
    update_form_decisions,
    claim_form,
    complete_form,
    mark_error,
)
from input_adapter import RawTextAdapter
from llm_router import process_transcript

# ── Logging ───────────────────────────────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger(__name__)

# ── FastAPI App ───────────────────────────────────────────────────
app = FastAPI(
    title="MedPull eClinicalWorks Server",
    description="Central API server for the MedPull kiosk and Chrome extension.",
    version="2.0.0",
)

# ── CORS — allow Chrome extension and deployed domains ────────────
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

class CreateFormRequest(BaseModel):
    patient_id: str = Field(..., min_length=1)
    transcript: str = Field(..., min_length=1)
    source: Optional[str] = Field(default="api")
    form_schema: Optional[list[dict[str, Any]]] = Field(default=None)


class ProcessFormRequest(BaseModel):
    form_schema: list[dict[str, Any]] = Field(default_factory=list)


class FormsListResponse(BaseModel):
    forms: list[FormRecord]


class LegacyProcessRequest(BaseModel):
    """Backward-compatible request body for POST /process."""
    transcript: str = Field(..., min_length=1)
    form_schema: list[dict[str, Any]] = Field(default_factory=list)


class HealthResponse(BaseModel):
    status: str = "ok"


# ── Health Check (no auth) ────────────────────────────────────────

@app.get("/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    return HealthResponse(status="ok")


# ── Legacy /process endpoint (backward compat) ───────────────────

@app.post("/process", response_model=FormDecisions)
async def legacy_process(
    request: LegacyProcessRequest,
    clinic_id: str = Depends(verify_api_key),
) -> FormDecisions:
    """
    Process a clinical transcript against a form schema.
    Kept for backward compatibility with older extension versions.
    """
    logger.info(
        "POST /process — clinic: %s, transcript length: %d, form fields: %d",
        clinic_id,
        len(request.transcript),
        len(request.form_schema),
    )

    try:
        adapter = RawTextAdapter(request.transcript)
        transcript = adapter.get_transcript()
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
        raise HTTPException(status_code=500, detail=f"Processing failed: {e}") from e


# ── Form Queue Endpoints ──────────────────────────────────────────

@app.post("/forms", response_model=FormRecord)
async def submit_form(
    request: CreateFormRequest,
    clinic_id: str = Depends(verify_api_key),
) -> FormRecord:
    """Submit a new form (transcript + optional schema) from kiosk or extension."""
    logger.info(
        "POST /forms — clinic: %s, patient: %s, source: %s",
        clinic_id,
        request.patient_id,
        request.source,
    )

    record = await create_form(
        clinic_id=clinic_id,
        patient_id=request.patient_id,
        transcript=request.transcript,
        source=request.source or "api",
        form_schema=request.form_schema,
    )
    return record


@app.get("/forms", response_model=FormsListResponse)
async def list_forms(
    clinic_id: str = Depends(verify_api_key),
    status: str = Query(default="pending,ready", description="Comma-separated status filter"),
) -> FormsListResponse:
    """List forms for this clinic, filtered by status."""
    statuses = [s.strip() for s in status.split(",") if s.strip()]
    logger.info("GET /forms — clinic: %s, statuses: %s", clinic_id, statuses)

    records = await list_forms_by_status(clinic_id, statuses)
    return FormsListResponse(forms=records)


@app.get("/forms/{form_id}", response_model=FormRecord)
async def get_single_form(
    form_id: str,
    clinic_id: str = Depends(verify_api_key),
) -> FormRecord:
    """Get a single form with its decisions."""
    record = await get_form(clinic_id, form_id)
    if record is None:
        raise HTTPException(status_code=404, detail=f"Form {form_id} not found")
    return record


@app.post("/forms/{form_id}/process", response_model=FormRecord)
async def process_single_form(
    form_id: str,
    request: ProcessFormRequest,
    clinic_id: str = Depends(verify_api_key),
) -> FormRecord:
    """Trigger LLM processing on a pending form."""
    record = await get_form(clinic_id, form_id)
    if record is None:
        raise HTTPException(status_code=404, detail=f"Form {form_id} not found")

    logger.info("POST /forms/%s/process — clinic: %s", form_id, clinic_id)

    schema = request.form_schema if request.form_schema else record.form_schema

    try:
        adapter = RawTextAdapter(record.transcript)
        transcript = adapter.get_transcript()
        decisions = await process_transcript(transcript, schema)

        updated = await update_form_decisions(
            clinic_id=clinic_id,
            form_id=form_id,
            decisions=decisions.decisions,
            missing_required=decisions.missing_required,
        )

        logger.info(
            "Form %s processed — %d decisions, %d missing required",
            form_id,
            len(decisions.decisions),
            len(decisions.missing_required),
        )
        return updated

    except Exception as e:
        logger.error("Processing failed for form %s: %s", form_id, e, exc_info=True)
        await mark_error(clinic_id, form_id, str(e))
        raise HTTPException(status_code=500, detail=f"Processing failed: {e}") from e


@app.post("/forms/{form_id}/claim", response_model=FormRecord)
async def claim_single_form(
    form_id: str,
    clinic_id: str = Depends(verify_api_key),
) -> FormRecord:
    """Claim a form before filling eCW (optimistic lock: status must be 'ready')."""
    logger.info("POST /forms/%s/claim — clinic: %s", form_id, clinic_id)

    try:
        record = await claim_form(clinic_id, form_id)
        return record
    except ValueError as e:
        raise HTTPException(status_code=409, detail=str(e)) from e


@app.post("/forms/{form_id}/complete", response_model=FormRecord)
async def complete_single_form(
    form_id: str,
    clinic_id: str = Depends(verify_api_key),
) -> FormRecord:
    """Mark a form as completed after clinician confirms the eCW fill."""
    logger.info("POST /forms/%s/complete — clinic: %s", form_id, clinic_id)

    record = await complete_form(clinic_id, form_id)
    return record
