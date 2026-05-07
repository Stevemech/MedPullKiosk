"""
nav_router.py — Grok-powered page navigation for eClinicalWorks.

Receives a structured DOM snapshot + a navigation goal and returns
the single next browser action to perform.  Uses grok-3-mini for
speed (navigation decisions are simpler than clinical extraction).
"""

from __future__ import annotations

import json
import logging
import os

from dotenv import load_dotenv

load_dotenv(override=False)

from litellm import completion

from nav_schema import (
    NavigateRequest,
    NavigateResponse,
    NavigationAction,
    NavActionType,
)

logger = logging.getLogger(__name__)

MODEL_NAV: str = os.getenv("MODEL_NAV", "xai/grok-3-mini")

NAV_SYSTEM_PROMPT: str = """You are a browser navigation assistant for eClinicalWorks (eCW), a medical EHR system.

You receive a structured snapshot of the current web page and a goal. Return the SINGLE next action to move toward that goal.

RULES:
1. Identify elements ONLY by their "index" from the elements array. Never fabricate selectors.
2. Available actions:
   - "click"  — click the element at element_index
   - "type"   — clear the field at element_index and type the value
   - "select" — choose an option in a dropdown at element_index
   - "wait"   — wait briefly (use when the page is loading or a modal is expected)
   - "done"   — the goal has been achieved (the page is in the desired state)
   - "error"  — the goal cannot be achieved from this page (explain in reasoning)
3. SAFETY:
   - NEVER click Delete, Remove, Cancel, or any destructive action.
   - NEVER navigate away from eClinicalWorks domains.
   - If unsure, return "wait" or "error" rather than guessing.
4. When searching for a patient, type the name in a search/filter field and then click Search or press Enter.
5. If asked to create a new patient and you see an "Add Patient" or "New Patient" link/button, click it.
6. When the goal says "open their chart" and you see the patient in a list/results, click on the patient's name or the row.
7. Return "done" ONLY when the page clearly shows the goal is achieved (e.g., you are on the patient's chart, or the search results show the patient).
8. Keep reasoning concise but clear. Think step-by-step BEFORE deciding.
9. If there is a modal/dialog on the page, interact with the modal first.
10. For typing actions, provide the complete value to type (don't type partial strings)."""


def _build_nav_prompt(request: NavigateRequest) -> str:
    elements_summary = json.dumps(
        [e.model_dump(exclude_none=True) for e in request.page_snapshot.elements],
        indent=1,
    )

    history_text = ""
    if request.history:
        lines = []
        for h in request.history[-5:]:
            lines.append(f"  - {h.action} element {h.element_index} "
                         f"{'value=' + repr(h.value) + ' ' if h.value else ''}"
                         f"→ {h.outcome or 'ok'}")
        history_text = f"\n## Recent Actions\n" + "\n".join(lines)

    patient_text = ""
    if request.patient_context:
        patient_text = f"\n## Patient Context\n{json.dumps(request.patient_context, indent=1)}"

    return f"""## Goal
{request.goal}

## Current Page
URL: {request.page_snapshot.url}
Title: {request.page_snapshot.title}
Has modal: {request.page_snapshot.has_modal}
Iframe count: {request.page_snapshot.iframe_count}

## Visible Text (first 2000 chars)
{request.page_snapshot.visible_text[:2000]}

## Interactive Elements
{elements_summary}
{history_text}{patient_text}

Return the single next action as JSON."""


_NAV_RESPONSE_SCHEMA: dict = {
    "type": "object",
    "properties": {
        "action": {
            "type": "string",
            "enum": ["click", "type", "select", "wait", "done", "error"],
        },
        "element_index": {"type": ["integer", "null"]},
        "value": {"type": ["string", "null"]},
        "reasoning": {"type": "string"},
        "done": {"type": "boolean"},
        "confidence": {"type": "number"},
    },
    "required": ["action", "element_index", "value", "reasoning", "done", "confidence"],
    "additionalProperties": False,
}


async def analyze_page(request: NavigateRequest) -> NavigateResponse:
    """
    Send the page snapshot + goal to Grok and parse the navigation action.
    """
    user_prompt = _build_nav_prompt(request)

    logger.info(
        "Navigation request — goal: %s, elements: %d, history: %d",
        request.goal[:80],
        len(request.page_snapshot.elements),
        len(request.history),
    )

    try:
        response = completion(
            model=MODEL_NAV,
            messages=[
                {"role": "system", "content": NAV_SYSTEM_PROMPT},
                {"role": "user", "content": user_prompt},
            ],
            response_format={
                "type": "json_schema",
                "json_schema": {
                    "name": "NavigationAction",
                    "strict": True,
                    "schema": _NAV_RESPONSE_SCHEMA,
                },
            },
            temperature=0.05,
        )

        content = response.choices[0].message.content
        if not content:
            raise ValueError("Grok returned empty navigation response.")

        parsed = json.loads(content)
        action = NavigationAction.model_validate(parsed)

        logger.info(
            "Navigation action — %s element=%s done=%s conf=%.2f",
            action.action.value,
            action.element_index,
            action.done,
            action.confidence,
        )

        return NavigateResponse(action=action, model_used=MODEL_NAV)

    except json.JSONDecodeError as e:
        logger.error("Failed to parse navigation JSON: %s", e)
        fallback = NavigationAction(
            action=NavActionType.ERROR,
            reasoning=f"LLM returned invalid JSON: {e}",
            done=False,
            confidence=0.0,
        )
        return NavigateResponse(action=fallback, model_used=MODEL_NAV)
    except Exception as e:
        logger.error("Navigation LLM call failed: %s", e, exc_info=True)
        fallback = NavigationAction(
            action=NavActionType.ERROR,
            reasoning=f"LLM call failed: {e}",
            done=False,
            confidence=0.0,
        )
        return NavigateResponse(action=fallback, model_used=MODEL_NAV)
