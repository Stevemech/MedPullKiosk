"""
auth.py — API key authentication middleware for MedPull.

Reads API keys and their clinic ID mappings from environment variables:
  MEDPULL_API_KEYS=key1,key2,...
  MEDPULL_KEY_MAP=key1:clinic_001,key2:clinic_002,...

Provides a FastAPI dependency that validates the X-API-Key header
and returns the associated clinic_id.
"""

from __future__ import annotations

import logging
import os
from typing import Optional

from fastapi import Header, HTTPException

logger = logging.getLogger(__name__)


def _load_key_map() -> dict[str, str]:
    """Parse MEDPULL_KEY_MAP into a {key: clinic_id} dict."""
    raw = os.getenv("MEDPULL_KEY_MAP", "")
    mapping: dict[str, str] = {}
    for entry in raw.split(","):
        entry = entry.strip()
        if ":" not in entry:
            continue
        key, clinic_id = entry.split(":", 1)
        mapping[key.strip()] = clinic_id.strip()
    return mapping


def _load_valid_keys() -> set[str]:
    """Parse MEDPULL_API_KEYS into a set of valid keys."""
    raw = os.getenv("MEDPULL_API_KEYS", "")
    return {k.strip() for k in raw.split(",") if k.strip()}


_key_map: Optional[dict[str, str]] = None
_valid_keys: Optional[set[str]] = None


def _get_key_map() -> dict[str, str]:
    global _key_map
    if _key_map is None:
        _key_map = _load_key_map()
    return _key_map


def _get_valid_keys() -> set[str]:
    global _valid_keys
    if _valid_keys is None:
        _valid_keys = _load_valid_keys()
    return _valid_keys


def reload_keys() -> None:
    """Force reload of API keys from environment (useful for testing)."""
    global _key_map, _valid_keys
    _key_map = None
    _valid_keys = None


async def verify_api_key(x_api_key: str = Header(..., alias="X-API-Key")) -> str:
    """
    FastAPI dependency that validates the X-API-Key header.

    Returns the clinic_id associated with the key.

    Raises:
        HTTPException 401 if the key is missing or invalid.
    """
    valid_keys = _get_valid_keys()
    key_map = _get_key_map()

    if not x_api_key or x_api_key not in valid_keys:
        logger.warning("Invalid API key attempted: %s...", x_api_key[:8] if x_api_key else "(empty)")
        raise HTTPException(
            status_code=401,
            detail="Invalid or missing API key",
        )

    clinic_id = key_map.get(x_api_key)
    if not clinic_id:
        logger.warning("API key valid but no clinic mapping: %s...", x_api_key[:8])
        raise HTTPException(
            status_code=401,
            detail="API key has no associated clinic",
        )

    return clinic_id
