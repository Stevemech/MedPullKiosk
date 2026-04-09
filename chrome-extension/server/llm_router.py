"""
llm_router.py — LiteLLM + Grok integration for structured field extraction.

Uses LiteLLM's completion() API exclusively (never imports the Grok SDK
directly). Supports automatic fallback from a fast/cheap model to a more
capable one when confidence is low or required fields are missing.

To swap the LLM provider, change MODEL_PRIMARY / MODEL_FALLBACK in your
.env file. For example:
    MODEL_PRIMARY=ollama/qwen2.5:14b
    MODEL_FALLBACK=ollama/qwen2.5:72b
"""

from __future__ import annotations

import json
import logging
import os
from typing import Any

from dotenv import load_dotenv

load_dotenv(override=False)

from litellm import completion

from form_schema import (
    FieldDecision,
    FormDecisions,
    get_response_json_schema,
)

logger = logging.getLogger(__name__)

# ── Model Configuration (one-line change to swap providers) ───────
MODEL_PRIMARY: str = os.getenv("MODEL_PRIMARY", "xai/grok-3-mini")
MODEL_FALLBACK: str = os.getenv("MODEL_FALLBACK", "xai/grok-3")

# ── Confidence Threshold ──────────────────────────────────────────
CONFIDENCE_THRESHOLD: float = 0.75

# ── System Prompt ─────────────────────────────────────────────────
SYSTEM_PROMPT: str = """You are a clinical data extraction assistant. Your task is to map information from a clinical transcript to specific form fields.

RULES — follow these strictly:
1. FILL a field ONLY if the value is clearly and explicitly stated in the transcript. Never infer, guess, or hallucinate clinical values.
2. SKIP a field if it is genuinely inapplicable to this encounter (provide reasoning).
3. FLAG a field if it is required but the transcript does not contain the information (provide reasoning explaining what is missing).
4. Your reasoning MUST appear BEFORE your action decision — think step-by-step before committing.
5. Confidence scores: 1.0 = verbatim from transcript, 0.8–0.99 = clearly implied, 0.5–0.79 = partially supported, <0.5 = uncertain.
6. For select/dropdown fields, match the value to one of the provided options exactly.
7. Never fabricate patient data. If unsure, FLAG the field.
8. Include every field from the form schema in your response — no field should be omitted."""


def _build_user_prompt(transcript: str, form_schema: list[dict[str, Any]]) -> str:
    """Build the user message containing the transcript and form schema."""
    schema_text = json.dumps(form_schema, indent=2)
    return f"""## Clinical Transcript
{transcript}

## Form Schema (JSON)
{schema_text}

Process every field in the form schema. For each field, provide your reasoning first, then decide whether to fill, skip, or flag it. Return the complete structured response."""


async def process_transcript(
    transcript: str,
    form_schema: list[dict[str, Any]],
) -> FormDecisions:
    """
    Process a clinical transcript against a form schema using LiteLLM.

    First attempts with the primary (fast/cheap) model. If any field
    confidence is below the threshold or required fields are missing,
    automatically retries with the fallback (more capable) model.

    Args:
        transcript: The clinical transcript text.
        form_schema: List of form field descriptors from the extension.

    Returns:
        FormDecisions with all field decisions and missing required fields.
    """
    response_schema = get_response_json_schema()
    user_prompt = _build_user_prompt(transcript, form_schema)

    # ── Primary model attempt ─────────────────────────────────────
    logger.info("Processing with primary model: %s", MODEL_PRIMARY)
    decisions = await _call_llm(MODEL_PRIMARY, user_prompt, response_schema)

    # ── Check if fallback is needed ───────────────────────────────
    needs_fallback = False

    if decisions.missing_required:
        logger.info(
            "Missing required fields detected: %s — triggering fallback",
            decisions.missing_required,
        )
        needs_fallback = True

    if not needs_fallback:
        for d in decisions.decisions:
            if d.confidence < CONFIDENCE_THRESHOLD:
                logger.info(
                    "Low confidence (%.2f) on field '%s' — triggering fallback",
                    d.confidence,
                    d.field_id,
                )
                needs_fallback = True
                break

    if needs_fallback and MODEL_PRIMARY != MODEL_FALLBACK:
        logger.info("Retrying with fallback model: %s", MODEL_FALLBACK)
        decisions = await _call_llm(MODEL_FALLBACK, user_prompt, response_schema)

    return decisions


async def _call_llm(
    model: str,
    user_prompt: str,
    response_schema: dict,
) -> FormDecisions:
    """
    Make a single LLM call via LiteLLM and parse the structured response.

    Args:
        model: The LiteLLM model string (e.g., "xai/grok-3-mini").
        user_prompt: The user message with transcript + form schema.
        response_schema: JSON Schema dict for response_format.

    Returns:
        Parsed FormDecisions.

    Raises:
        ValueError: If the LLM response cannot be parsed.
    """
    try:
        response = completion(
            model=model,
            messages=[
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": user_prompt},
            ],
            response_format={
                "type": "json_schema",
                "json_schema": {
                    "name": "FormDecisions",
                    "strict": True,
                    "schema": response_schema,
                },
            },
            temperature=0.1,  # Low temperature for deterministic clinical extraction
        )

        content = response.choices[0].message.content
        if not content:
            raise ValueError("LLM returned an empty response.")

        logger.debug("Raw LLM response: %s", content[:500])

        # Parse and validate with Pydantic
        parsed = json.loads(content)
        decisions = FormDecisions.model_validate(parsed)

        return decisions

    except json.JSONDecodeError as e:
        logger.error("Failed to parse LLM JSON response: %s", e)
        raise ValueError(f"LLM returned invalid JSON: {e}") from e
    except Exception as e:
        logger.error("LLM call failed (model=%s): %s", model, e)
        raise
