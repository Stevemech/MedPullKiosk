"""
nav_schema.py — Pydantic models for Grok-powered page navigation.

Defines the request/response shapes for the POST /navigate endpoint.
The extension sends a structured DOM snapshot + a goal; the server
returns the single next action to perform.
"""

from __future__ import annotations

from enum import Enum
from typing import Any, Optional

from pydantic import BaseModel, Field


class NavActionType(str, Enum):
    CLICK = "click"
    TYPE = "type"
    SELECT = "select"
    WAIT = "wait"
    DONE = "done"
    ERROR = "error"


class PageElement(BaseModel):
    """One interactive or informational element from the page snapshot."""

    index: int = Field(..., description="Positional index in the snapshot array.")
    tag: str = Field(..., description="HTML tag name (INPUT, BUTTON, A, …).")
    type: Optional[str] = Field(default=None, description="input type attribute.")
    id: Optional[str] = Field(default=None)
    name: Optional[str] = Field(default=None)
    text: Optional[str] = Field(default=None, description="Visible text content (trimmed).")
    placeholder: Optional[str] = Field(default=None)
    value: Optional[str] = Field(default=None, description="Current value for inputs.")
    href: Optional[str] = Field(default=None)
    classes: Optional[str] = Field(default=None, description="Space-separated class list.")
    aria_label: Optional[str] = Field(default=None)
    role: Optional[str] = Field(default=None)
    disabled: bool = Field(default=False)


class NavigateRequest(BaseModel):
    """Sent by the extension to request the next navigation action."""

    goal: str = Field(
        ...,
        description="Plain-English description of what the user wants to achieve on this page.",
    )
    page_snapshot: "PageSnapshot" = Field(
        ...,
        description="Structured summary of the current page state.",
    )
    history: list["HistoryEntry"] = Field(
        default_factory=list,
        description="Previous actions taken toward this goal (for context).",
    )
    patient_context: Optional[dict[str, Any]] = Field(
        default=None,
        description="Patient info from the form record (name, id, transcript excerpt).",
    )


class PageSnapshot(BaseModel):
    url: str
    title: str
    visible_text: str = Field(
        ...,
        description="First ~2000 chars of visible page text for overall context.",
    )
    elements: list[PageElement] = Field(
        ...,
        description="All interactive elements (inputs, buttons, links, selects, tabs).",
    )
    iframe_count: int = Field(default=0)
    has_modal: bool = Field(default=False, description="True if an overlay / dialog is detected.")


class HistoryEntry(BaseModel):
    action: str
    element_index: Optional[int] = None
    value: Optional[str] = None
    outcome: Optional[str] = None


class NavigationAction(BaseModel):
    """Single action returned by Grok for the extension to execute."""

    action: NavActionType = Field(
        ...,
        description="The type of browser action to perform.",
    )
    element_index: Optional[int] = Field(
        default=None,
        description="Index of the target element in the snapshot (required for click/type/select).",
    )
    value: Optional[str] = Field(
        default=None,
        description="Text to type or option to select.",
    )
    reasoning: str = Field(
        ...,
        description="Step-by-step explanation of why this action was chosen.",
    )
    done: bool = Field(
        default=False,
        description="True when the navigation goal has been achieved.",
    )
    confidence: float = Field(
        ...,
        ge=0.0,
        le=1.0,
        description="Confidence that this is the correct next action.",
    )

    model_config = {"json_schema_extra": {"additionalProperties": False}}


class NavigateResponse(BaseModel):
    """Top-level response wrapper from POST /navigate."""

    action: NavigationAction
    model_used: str = Field(..., description="Which LLM model produced this action.")

    model_config = {"json_schema_extra": {"additionalProperties": False}}
