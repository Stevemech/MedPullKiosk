"""
form_schema.py — Pydantic models for LLM field decisions.

Defines the structured output schema that the LLM must conform to.
The JSON Schema generated from these models is passed to LiteLLM's
response_format parameter to guarantee well-formed responses.
"""

from __future__ import annotations

from enum import Enum
from typing import Optional

from pydantic import BaseModel, Field


class FieldAction(str, Enum):
    """Allowed actions the LLM can assign to each form field."""

    FILL = "fill"
    SKIP = "skip"
    FLAG = "flag"


class FieldDecision(BaseModel):
    """
    A single field decision produced by the LLM.

    NOTE: `reasoning` appears before `action` intentionally — this forces
    chain-of-thought before the model commits to an action.
    """

    field_id: str = Field(
        ...,
        description="The unique identifier of the form field.",
    )
    label: str = Field(
        ...,
        description="Human-readable label of the form field.",
    )
    reasoning: str = Field(
        ...,
        description=(
            "Step-by-step reasoning explaining why this action was chosen. "
            "Must appear before the action to enforce chain-of-thought."
        ),
    )
    action: FieldAction = Field(
        ...,
        description=(
            "The action to take: 'fill' if the value is clearly stated in the "
            "transcript, 'skip' if genuinely inapplicable, 'flag' if required "
            "but absent from the transcript."
        ),
    )
    value: Optional[str] = Field(
        default=None,
        description=(
            "The value to fill into the field. Required when action is 'fill', "
            "null otherwise."
        ),
    )
    confidence: float = Field(
        ...,
        ge=0.0,
        le=1.0,
        description="Confidence score from 0.0 to 1.0 for this decision.",
    )

    model_config = {
        "json_schema_extra": {
            "additionalProperties": False,
        }
    }


class FormDecisions(BaseModel):
    """
    Complete set of field decisions returned by the LLM for a given
    transcript + form schema pair.
    """

    decisions: list[FieldDecision] = Field(
        ...,
        description="Ordered list of decisions for each form field.",
    )
    missing_required: list[str] = Field(
        default_factory=list,
        description=(
            "List of required field labels/IDs that the LLM could not find "
            "values for in the transcript."
        ),
    )

    model_config = {
        "json_schema_extra": {
            "additionalProperties": False,
        }
    }


def get_response_json_schema() -> dict:
    """
    Generate the JSON Schema dict suitable for passing to LiteLLM's
    response_format parameter.

    Returns a schema with additionalProperties: false and all fields
    marked required to guarantee completeness.
    """
    schema = FormDecisions.model_json_schema()

    # Ensure top-level additionalProperties is false
    schema["additionalProperties"] = False

    # Ensure all defined fields are required at every level
    _enforce_required(schema)

    # Resolve $defs references inline for providers that don't support them
    if "$defs" in schema:
        schema = _resolve_refs(schema, schema.get("$defs", {}))

    return schema


def _enforce_required(schema: dict) -> None:
    """Recursively ensure every object's properties are all listed as required."""
    if schema.get("type") == "object" and "properties" in schema:
        schema["required"] = list(schema["properties"].keys())
        schema["additionalProperties"] = False
        for prop in schema["properties"].values():
            _enforce_required(prop)
    if schema.get("type") == "array" and "items" in schema:
        _enforce_required(schema["items"])
    for definition in schema.get("$defs", {}).values():
        _enforce_required(definition)


def _resolve_refs(obj: dict | list | str | int | float | bool | None, defs: dict) -> dict | list | str | int | float | bool | None:
    """Recursively resolve $ref pointers using $defs."""
    if isinstance(obj, dict):
        if "$ref" in obj:
            ref_path = obj["$ref"]  # e.g., "#/$defs/FieldDecision"
            ref_name = ref_path.split("/")[-1]
            if ref_name in defs:
                resolved = dict(defs[ref_name])
                resolved.pop("title", None)
                return _resolve_refs(resolved, defs)
            return obj
        result = {}
        for k, v in obj.items():
            if k == "$defs":
                continue  # strip $defs from output
            result[k] = _resolve_refs(v, defs)
        return result
    if isinstance(obj, list):
        return [_resolve_refs(item, defs) for item in obj]
    return obj
