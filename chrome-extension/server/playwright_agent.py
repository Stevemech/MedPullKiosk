"""
playwright_agent.py — eClinicalWorks Playwright browser automation.

Connects to an already-running Chrome instance via CDP (Chrome DevTools
Protocol), iterates through confirmed field decisions, and fills the
eClinicalWorks form. The clinician can watch every action in real time.

SAFETY GUARANTEES:
  - No auto-submit: this module only fills fields and highlights flags.
  - Every step is wrapped in try/except to prevent patient record corruption.
  - A post-fill screenshot is always returned for clinician review.
"""

from __future__ import annotations

import base64
import logging
import os
from typing import Any

from dotenv import load_dotenv
from playwright.async_api import async_playwright, Browser, Page

from form_schema import FieldAction, FieldDecision

load_dotenv()

logger = logging.getLogger(__name__)

CDP_ENDPOINT: str = os.getenv("CDP_ENDPOINT", "http://localhost:9222")

# Delay between field fills to avoid overwhelming eCW's reactivity (ms)
FILL_DELAY_MS: int = 200


async def execute_decisions(
    decisions: list[FieldDecision],
    ecw_url: str | None = None,
) -> dict[str, Any]:
    """
    Execute confirmed field decisions on the eClinicalWorks form.

    Connects to the existing Chrome session via CDP, fills fields marked
    as 'fill', highlights fields marked as 'flag', and skips the rest.

    Args:
        decisions: List of FieldDecision objects confirmed by the clinician.
        ecw_url: Optional URL to navigate to. If None, uses the current page.

    Returns:
        dict with keys:
            success (bool): True if all fills succeeded.
            errors (list[str]): Error messages for any failed fields.
            screenshot_base64 (str): Base64-encoded PNG screenshot of the page.
    """
    errors: list[str] = []
    screenshot_b64: str = ""

    try:
        async with async_playwright() as pw:
            browser: Browser = await pw.chromium.connect_over_cdp(CDP_ENDPOINT)

            # Use the first available context and page (eCW is already open)
            contexts = browser.contexts
            if not contexts:
                return {
                    "success": False,
                    "errors": [
                        "No browser contexts found. Ensure eClinicalWorks is "
                        "open in the Chrome instance with remote debugging enabled."
                    ],
                    "screenshot_base64": "",
                }

            page: Page = contexts[0].pages[0] if contexts[0].pages else await contexts[0].new_page()

            # Optionally navigate to a specific eCW URL
            if ecw_url:
                try:
                    await page.goto(ecw_url, wait_until="networkidle", timeout=30000)
                except Exception as e:
                    logger.warning("Navigation to %s failed: %s", ecw_url, e)

            # ── Process each decision ─────────────────────────────
            for decision in decisions:
                try:
                    if decision.action == FieldAction.FILL:
                        await _fill_field(page, decision)
                    elif decision.action == FieldAction.FLAG:
                        await _highlight_flagged_field(page, decision)
                    # SKIP: do nothing
                except Exception as e:
                    error_msg = f"Field '{decision.label or decision.field_id}' failed: {e}"
                    logger.error(error_msg)
                    errors.append(error_msg)

            # ── Take post-fill screenshot ─────────────────────────
            try:
                screenshot_bytes = await page.screenshot(full_page=True, type="png")
                screenshot_b64 = base64.b64encode(screenshot_bytes).decode("utf-8")
            except Exception as e:
                logger.error("Screenshot failed: %s", e)
                errors.append(f"Screenshot capture failed: {e}")

    except Exception as e:
        logger.error("Playwright connection failed: %s", e)
        return {
            "success": False,
            "errors": [
                f"Could not connect to Chrome via CDP at {CDP_ENDPOINT}. "
                f"Ensure Chrome is running with --remote-debugging-port=9222. "
                f"Error: {e}"
            ],
            "screenshot_base64": "",
        }

    return {
        "success": len(errors) == 0,
        "errors": errors,
        "screenshot_base64": screenshot_b64,
    }


async def _fill_field(page: Page, decision: FieldDecision) -> None:
    """
    Fill a single form field with the decided value.

    Locates the element by id → name → label text (fallback chain),
    sets the value, and dispatches input + change events for framework
    change detection.
    """
    if decision.value is None:
        logger.warning("Fill action for '%s' but value is None — skipping", decision.field_id)
        return

    locator = _get_locator(page, decision)
    element = locator.first

    # Wait for the element to be visible (up to 5 seconds)
    try:
        await element.wait_for(state="visible", timeout=5000)
    except Exception:
        logger.warning("Element '%s' not visible within timeout", decision.field_id)

    # Determine element type and fill accordingly
    tag_name = await element.evaluate("el => el.tagName.toLowerCase()")

    if tag_name == "select":
        # Try to select by value first, then by label
        try:
            await element.select_option(value=decision.value)
        except Exception:
            try:
                await element.select_option(label=decision.value)
            except Exception as e:
                raise RuntimeError(
                    f"Could not select option '{decision.value}' in select element: {e}"
                ) from e
    elif await element.evaluate("el => el.type") in ("checkbox", "radio"):
        should_check = decision.value.lower() in ("true", "yes", "1", "on")
        is_checked = await element.is_checked()
        if should_check != is_checked:
            await element.click()
    else:
        # Text-like input: clear and type
        await element.fill("")
        await element.fill(decision.value)

    # Dispatch events for React/Angular change detection
    await element.evaluate("""el => {
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
    }""")

    # Brief delay to avoid overwhelming eCW
    await page.wait_for_timeout(FILL_DELAY_MS)

    logger.info("Filled field '%s' with value '%s'", decision.field_id, decision.value)


async def _highlight_flagged_field(page: Page, decision: FieldDecision) -> None:
    """
    Scroll to a flagged field and highlight it with a red outline so the
    clinician can see what needs manual input.
    """
    locator = _get_locator(page, decision)
    element = locator.first

    try:
        await element.wait_for(state="visible", timeout=5000)
    except Exception:
        logger.warning("Flagged element '%s' not visible", decision.field_id)
        return

    await element.evaluate("""(el, reasoning) => {
        el.style.outline = '3px solid #dc2626';
        el.style.outlineOffset = '2px';
        el.title = 'Needs manual input: ' + reasoning;
        el.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }""", decision.reasoning or "Required but not found in transcript")

    await page.wait_for_timeout(FILL_DELAY_MS)

    logger.info("Flagged field '%s': %s", decision.field_id, decision.reasoning)


def _get_locator(page: Page, decision: FieldDecision):
    """
    Build a Playwright locator using the fallback chain:
    id → name → label text.
    """
    field_id = decision.field_id
    label = decision.label

    if field_id:
        # Try by id
        by_id = page.locator(f"#{field_id}")
        # Also prepare by name as fallback
        by_name = page.locator(f"[name='{field_id}']")

        # Use a combined locator: id OR name
        combined = page.locator(f"#{field_id}, [name='{field_id}']")
        return combined

    if label:
        # Fallback to label text
        return page.get_by_label(label)

    raise RuntimeError(
        f"Cannot locate field: no id or label available for decision."
    )
