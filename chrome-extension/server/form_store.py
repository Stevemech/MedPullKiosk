"""
form_store.py — DynamoDB-backed form queue for MedPull.

Stores filled form records with lifecycle status tracking.
Uses aioboto3 for async DynamoDB access and supports a local
DynamoDB endpoint for development via DYNAMODB_ENDPOINT env var.
"""

from __future__ import annotations

import os
import logging
from datetime import datetime, timezone, timedelta
from enum import Enum
from typing import Any, Optional

import aioboto3
import ulid
from pydantic import BaseModel, Field

from form_schema import FieldDecision

logger = logging.getLogger(__name__)

TABLE_NAME: str = os.getenv("DYNAMODB_TABLE", "medpull-forms")
TTL_DAYS: int = 30


class FormStatus(str, Enum):
    PENDING = "pending"
    PROCESSING = "processing"
    READY = "ready"
    CLAIMED = "claimed"
    COMPLETED = "completed"
    ERROR = "error"


class FormRecord(BaseModel):
    clinic_id: str
    form_id: str
    patient_id: str
    status: FormStatus
    transcript: str
    form_schema: list[dict[str, Any]] = Field(default_factory=list)
    decisions: Optional[list[FieldDecision]] = None
    missing_required: list[str] = Field(default_factory=list)
    source: str = "api"
    created_at: str
    updated_at: str
    error_message: Optional[str] = None
    ttl: int


def _get_session_kwargs() -> dict[str, Any]:
    """Return boto3 session kwargs, adding endpoint_url for local DynamoDB."""
    endpoint = os.getenv("DYNAMODB_ENDPOINT")
    kwargs: dict[str, Any] = {
        "region_name": os.getenv("AWS_DEFAULT_REGION", "us-east-1"),
    }
    if endpoint:
        kwargs["endpoint_url"] = endpoint
    return kwargs


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def _ttl_epoch() -> int:
    return int((datetime.now(timezone.utc) + timedelta(days=TTL_DAYS)).timestamp())


def _item_to_record(item: dict[str, Any]) -> FormRecord:
    """Convert a raw DynamoDB item dict into a FormRecord."""
    decisions_raw = item.get("decisions")
    decisions = None
    if decisions_raw is not None:
        import json
        decisions = [FieldDecision.model_validate(d) for d in json.loads(decisions_raw)]

    missing_raw = item.get("missing_required")
    missing: list[str] = []
    if missing_raw is not None:
        import json
        missing = json.loads(missing_raw)

    schema_raw = item.get("form_schema", "[]")
    import json
    form_schema = json.loads(schema_raw) if isinstance(schema_raw, str) else schema_raw

    return FormRecord(
        clinic_id=item["clinic_id"],
        form_id=item["form_id"],
        patient_id=item.get("patient_id", ""),
        status=FormStatus(item["status"]),
        transcript=item.get("transcript", ""),
        form_schema=form_schema,
        decisions=decisions,
        missing_required=missing,
        source=item.get("source", "api"),
        created_at=item.get("created_at", ""),
        updated_at=item.get("updated_at", ""),
        error_message=item.get("error_message"),
        ttl=int(item.get("ttl", 0)),
    )


def _record_to_item(record: FormRecord) -> dict[str, Any]:
    """Convert a FormRecord to a DynamoDB-compatible item dict."""
    import json

    item: dict[str, Any] = {
        "clinic_id": record.clinic_id,
        "form_id": record.form_id,
        "patient_id": record.patient_id,
        "status": record.status.value,
        "transcript": record.transcript,
        "form_schema": json.dumps([d for d in record.form_schema]),
        "source": record.source,
        "created_at": record.created_at,
        "updated_at": record.updated_at,
        "ttl": record.ttl,
    }

    if record.decisions is not None:
        item["decisions"] = json.dumps([d.model_dump() for d in record.decisions])

    item["missing_required"] = json.dumps(record.missing_required)

    if record.error_message is not None:
        item["error_message"] = record.error_message

    return item


_session = aioboto3.Session()


async def _get_table():
    """Get a DynamoDB Table resource."""
    kwargs = _get_session_kwargs()
    endpoint_url = kwargs.pop("endpoint_url", None)
    resource_kwargs: dict[str, Any] = {"region_name": kwargs.get("region_name", "us-east-1")}
    if endpoint_url:
        resource_kwargs["endpoint_url"] = endpoint_url
    return _session.resource("dynamodb", **resource_kwargs)


async def create_form(
    clinic_id: str,
    patient_id: str,
    transcript: str,
    source: str = "api",
    form_schema: Optional[list[dict[str, Any]]] = None,
) -> FormRecord:
    """Insert a new form record with status='pending'."""
    now = _now_iso()
    record = FormRecord(
        clinic_id=clinic_id,
        form_id=str(ulid.new()),
        patient_id=patient_id,
        status=FormStatus.PENDING,
        transcript=transcript,
        form_schema=form_schema or [],
        decisions=None,
        missing_required=[],
        source=source,
        created_at=now,
        updated_at=now,
        error_message=None,
        ttl=_ttl_epoch(),
    )

    async with await _get_table() as dynamo:
        table = await dynamo.Table(TABLE_NAME)
        await table.put_item(Item=_record_to_item(record))

    logger.info("Created form %s for clinic %s", record.form_id, clinic_id)
    return record


async def get_form(clinic_id: str, form_id: str) -> Optional[FormRecord]:
    """Retrieve a single form by clinic_id + form_id."""
    async with await _get_table() as dynamo:
        table = await dynamo.Table(TABLE_NAME)
        resp = await table.get_item(Key={"clinic_id": clinic_id, "form_id": form_id})

    item = resp.get("Item")
    if not item:
        return None
    return _item_to_record(item)


async def list_pending_forms(clinic_id: str) -> list[FormRecord]:
    """Return forms with status in ('pending', 'ready'), ordered by created_at ascending."""
    from boto3.dynamodb.conditions import Key, Attr

    async with await _get_table() as dynamo:
        table = await dynamo.Table(TABLE_NAME)
        resp = await table.query(
            KeyConditionExpression=Key("clinic_id").eq(clinic_id),
            FilterExpression=Attr("status").is_in(["pending", "ready"]),
        )

    items = resp.get("Items", [])
    records = [_item_to_record(item) for item in items]
    records.sort(key=lambda r: r.created_at)
    return records


async def list_forms_by_status(clinic_id: str, statuses: list[str]) -> list[FormRecord]:
    """Return forms matching any of the given statuses, ordered by created_at ascending."""
    from boto3.dynamodb.conditions import Key, Attr

    async with await _get_table() as dynamo:
        table = await dynamo.Table(TABLE_NAME)
        resp = await table.query(
            KeyConditionExpression=Key("clinic_id").eq(clinic_id),
            FilterExpression=Attr("status").is_in(statuses),
        )

    items = resp.get("Items", [])
    records = [_item_to_record(item) for item in items]
    records.sort(key=lambda r: r.created_at)
    return records


async def update_form_decisions(
    clinic_id: str,
    form_id: str,
    decisions: list[FieldDecision],
    missing_required: list[str],
) -> FormRecord:
    """Set decisions + status='ready' on a form."""
    import json

    now = _now_iso()
    async with await _get_table() as dynamo:
        table = await dynamo.Table(TABLE_NAME)
        await table.update_item(
            Key={"clinic_id": clinic_id, "form_id": form_id},
            UpdateExpression="SET #s = :s, decisions = :d, missing_required = :m, updated_at = :u",
            ExpressionAttributeNames={"#s": "status"},
            ExpressionAttributeValues={
                ":s": FormStatus.READY.value,
                ":d": json.dumps([d.model_dump() for d in decisions]),
                ":m": json.dumps(missing_required),
                ":u": now,
            },
        )

    record = await get_form(clinic_id, form_id)
    if record is None:
        raise ValueError(f"Form {form_id} not found after update")
    logger.info("Updated decisions for form %s — %d decisions", form_id, len(decisions))
    return record


async def claim_form(clinic_id: str, form_id: str) -> FormRecord:
    """Set status='claimed' with optimistic locking (status must be 'ready')."""
    now = _now_iso()
    async with await _get_table() as dynamo:
        table = await dynamo.Table(TABLE_NAME)
        try:
            await table.update_item(
                Key={"clinic_id": clinic_id, "form_id": form_id},
                UpdateExpression="SET #s = :new_s, updated_at = :u",
                ConditionExpression="#s = :expected_s",
                ExpressionAttributeNames={"#s": "status"},
                ExpressionAttributeValues={
                    ":new_s": FormStatus.CLAIMED.value,
                    ":expected_s": FormStatus.READY.value,
                    ":u": now,
                },
            )
        except Exception as e:
            if "ConditionalCheckFailedException" in str(type(e).__name__) or "ConditionalCheckFailed" in str(e):
                raise ValueError(
                    f"Form {form_id} cannot be claimed — status is not 'ready'"
                ) from e
            raise

    record = await get_form(clinic_id, form_id)
    if record is None:
        raise ValueError(f"Form {form_id} not found after claim")
    logger.info("Claimed form %s", form_id)
    return record


async def complete_form(clinic_id: str, form_id: str) -> FormRecord:
    """Set status='completed'."""
    now = _now_iso()
    async with await _get_table() as dynamo:
        table = await dynamo.Table(TABLE_NAME)
        await table.update_item(
            Key={"clinic_id": clinic_id, "form_id": form_id},
            UpdateExpression="SET #s = :s, updated_at = :u",
            ExpressionAttributeNames={"#s": "status"},
            ExpressionAttributeValues={
                ":s": FormStatus.COMPLETED.value,
                ":u": now,
            },
        )

    record = await get_form(clinic_id, form_id)
    if record is None:
        raise ValueError(f"Form {form_id} not found after complete")
    logger.info("Completed form %s", form_id)
    return record


async def mark_error(clinic_id: str, form_id: str, error_message: str) -> FormRecord:
    """Set status='error' with an error message."""
    now = _now_iso()
    async with await _get_table() as dynamo:
        table = await dynamo.Table(TABLE_NAME)
        await table.update_item(
            Key={"clinic_id": clinic_id, "form_id": form_id},
            UpdateExpression="SET #s = :s, error_message = :e, updated_at = :u",
            ExpressionAttributeNames={"#s": "status"},
            ExpressionAttributeValues={
                ":s": FormStatus.ERROR.value,
                ":e": error_message,
                ":u": now,
            },
        )

    record = await get_form(clinic_id, form_id)
    if record is None:
        raise ValueError(f"Form {form_id} not found after marking error")
    logger.info("Marked form %s as error: %s", form_id, error_message)
    return record
