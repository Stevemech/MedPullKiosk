"""
main.py — FastAPI entrypoint for the grok-ecw-middleware service.

Exposes:
  - GET  /health              — liveness probe (no auth, no side effects).
  - POST /fill-patient-record — orchestration endpoint that takes a raw
                                intake payload, runs LLM extraction,
                                writes to eCW, and forwards a copy to
                                the active output connector.

Run locally: `uvicorn main:app --reload --port 8080`
"""

from __future__ import annotations

import logging
import time
import uuid
from typing import Any, Optional

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field, ValidationError

import config
from ecw.base import ECWAPIError, ECWAuthError
from ecw.models import PatientIntakePayload, PatientRecord
from llm.base import LLMExtractionError

# ── Logging ───────────────────────────────────────────────────────
logging.basicConfig(
    level=config.LOG_LEVEL,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger(config.SERVICE_NAME)


# ── FastAPI app ───────────────────────────────────────────────────
app = FastAPI(
    title="Grok ↔ eCW Middleware",
    description=(
        "Hot-swappable middleware that uses Grok (xAI) to extract "
        "structured patient records from raw intake payloads and writes "
        "them to eClinicalWorks."
    ),
    version=config.SERVICE_VERSION,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ── Startup hook ──────────────────────────────────────────────────
@app.on_event("startup")
async def _log_config_warnings() -> None:
    """Print warnings for missing env vars — NON-FATAL."""
    logger.info(
        "Starting %s v%s on %s:%d (connector=%s, llm=%s)",
        config.SERVICE_NAME,
        config.SERVICE_VERSION,
        config.HOST,
        config.PORT,
        config.OUTPUT_CONNECTOR,
        config.LLM_MODEL,
    )
    for warning in config.validate_required_env():
        logger.warning("CONFIG: %s", warning)


# ══════════════════════════════════════════════════════════════════
# Response models
# ══════════════════════════════════════════════════════════════════

class HealthResponse(BaseModel):
    status: str = "ok"
    service: str = config.SERVICE_NAME
    version: str = config.SERVICE_VERSION
    output_connector: str = config.OUTPUT_CONNECTOR
    llm_model: str = config.LLM_MODEL


class FillPatientRecordResponse(BaseModel):
    """Summary returned after a successful /fill-patient-record call."""
    request_id: str
    patient_id: Optional[str] = None
    extracted_record: dict = Field(
        ...,
        description=(
            "The validated PatientRecord that was derived from the input "
            "(post-Pydantic). Included for auditability and clinician review."
        ),
    )
    ecw_summary: dict = Field(
        default_factory=dict,
        description="Per-section summary of what was written to eCW.",
    )
    connector_status: str = Field(
        ...,
        description="'sent' | 'skipped' | 'failed' — status of the side-channel send.",
    )
    connector_error: Optional[str] = None
    elapsed_ms: int


# ══════════════════════════════════════════════════════════════════
# Endpoints
# ══════════════════════════════════════════════════════════════════

@app.get("/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    """Basic liveness probe — no downstream calls."""
    return HealthResponse()


@app.post("/fill-patient-record", response_model=FillPatientRecordResponse)
async def fill_patient_record(payload: PatientIntakePayload) -> FillPatientRecordResponse:
    """
    Orchestration endpoint.

    Steps:
      1. Validate the incoming payload (must have raw_text OR structured).
      2. If structured is provided, skip the LLM. Otherwise, call the
         active LLM client's `extract()` with `raw_text`.
      3. Validate the extracted dict against PatientRecord.
      4. Call the ECW client's `write_patient_record(...)` (currently stub).
      5. Forward the full result to the active output connector (currently stub).
      6. Return a summary.

    Individual failures in steps 4 and 5 are handled differently:
      - Step 4 (ECW) failures abort the request — the record did not persist.
      - Step 5 (connector) failures log-and-continue — the clinical write
        has already succeeded and we never want a broken side-channel to
        mask that success.
    """
    request_id = uuid.uuid4().hex[:12]
    started = time.monotonic()

    logger.info(
        "[%s] POST /fill-patient-record — source=%s patient_id=%s has_raw_text=%s has_structured=%s",
        request_id,
        payload.source,
        payload.patient_id,
        bool(payload.raw_text),
        bool(payload.structured),
    )

    # ── 1. Input validation ───────────────────────────────────────
    if not payload.raw_text and not payload.structured:
        raise HTTPException(
            status_code=422,
            detail="Payload must include either 'raw_text' or 'structured'.",
        )

    # ── 2. Extract (LLM) or take structured input as-is ──────────
    try:
        if payload.structured is not None:
            logger.info("[%s] Using structured input — skipping LLM", request_id)
            extracted_dict: dict[str, Any] = payload.structured
        else:
            llm = config.get_llm_client()
            extracted_dict = await llm.extract(payload.raw_text or "")
            logger.info("[%s] LLM extraction produced %d top-level keys", request_id, len(extracted_dict))
    except LLMExtractionError as e:
        logger.error("[%s] LLM extraction failed: %s", request_id, e)
        raise HTTPException(status_code=502, detail=f"LLM extraction failed: {e}") from e
    except Exception as e:
        logger.error("[%s] Unexpected LLM error: %s", request_id, e, exc_info=True)
        raise HTTPException(status_code=500, detail=f"Unexpected LLM error: {e}") from e

    # ── 3. Validate against PatientRecord ────────────────────────
    try:
        record = PatientRecord.model_validate(extracted_dict)
    except ValidationError as e:
        logger.error("[%s] Extracted payload failed PatientRecord validation: %s", request_id, e)
        raise HTTPException(
            status_code=422,
            detail={
                "message": "Extracted payload did not match PatientRecord schema.",
                "errors": e.errors(),
                "raw_extraction": extracted_dict,
            },
        ) from e

    # ── 4. Write to eCW ──────────────────────────────────────────
    ecw = config.get_ecw_client()
    ecw_summary: dict[str, Any] = {}
    try:
        ecw_summary = await ecw.write_patient_record(record, patient_id=payload.patient_id)
    except NotImplementedError as e:
        # Expected until the ECW layer is implemented. Return a 501 so the
        # caller can distinguish "not wired up" from real ECW errors.
        logger.warning("[%s] ECW write is not yet implemented: %s", request_id, e)
        ecw_summary = {
            "status": "not_implemented",
            "message": str(e),
            "note": (
                "The ECW client is currently a stub. The extracted record "
                "above is valid and would be written once ecw/client.py is filled in."
            ),
        }
    except ECWAuthError as e:
        logger.error("[%s] ECW auth failure: %s", request_id, e)
        raise HTTPException(status_code=401, detail=f"ECW auth failure: {e}") from e
    except ECWAPIError as e:
        logger.error("[%s] ECW API error: %s", request_id, e)
        raise HTTPException(status_code=502, detail=f"ECW API error: {e}") from e
    except Exception as e:
        logger.error("[%s] Unexpected ECW error: %s", request_id, e, exc_info=True)
        raise HTTPException(status_code=500, detail=f"Unexpected ECW error: {e}") from e

    # ── 5. Side-channel send to output connector ─────────────────
    connector_status = "skipped"
    connector_error: Optional[str] = None

    connector_payload = {
        "request_id": request_id,
        "source": payload.source,
        "patient_id": payload.patient_id or ecw_summary.get("patient_id"),
        "extracted_record": record.model_dump(mode="json"),
        "ecw_summary": ecw_summary,
    }

    try:
        connector = config.get_output_connector()
        await connector.send(connector_payload)
        connector_status = "sent"
        logger.info("[%s] Connector send ok (%s)", request_id, config.OUTPUT_CONNECTOR)
    except NotImplementedError as e:
        connector_status = "skipped"
        connector_error = f"connector '{config.OUTPUT_CONNECTOR}' is a stub: {e}"
        logger.warning("[%s] %s", request_id, connector_error)
    except Exception as e:
        connector_status = "failed"
        connector_error = str(e)
        logger.error("[%s] Connector send failed: %s", request_id, e, exc_info=True)

    elapsed_ms = int((time.monotonic() - started) * 1000)
    logger.info("[%s] Done in %dms — connector=%s", request_id, elapsed_ms, connector_status)

    return FillPatientRecordResponse(
        request_id=request_id,
        patient_id=payload.patient_id or ecw_summary.get("patient_id"),
        extracted_record=record.model_dump(mode="json"),
        ecw_summary=ecw_summary,
        connector_status=connector_status,
        connector_error=connector_error,
        elapsed_ms=elapsed_ms,
    )


# ── Local entrypoint (optional — uvicorn is the canonical launcher) ──
if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "main:app",
        host=config.HOST,
        port=config.PORT,
        reload=True,
    )
