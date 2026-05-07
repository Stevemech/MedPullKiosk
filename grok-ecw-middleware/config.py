"""
config.py — centralized configuration for the grok-ecw-middleware service.

All values come from environment variables (loaded from a `.env` file via
python-dotenv during local development, or injected directly in a container
environment like AWS ECS / Fargate).

Hot-swap rules:
  - To change the LLM provider, implement a new subclass of BaseLLMClient
    and return it from `get_llm_client()`. The rest of the system does not
    need to change.
  - To change the output destination, implement a new subclass of
    BaseOutputConnector and register it in `_CONNECTOR_REGISTRY`. The
    `OUTPUT_CONNECTOR` env var selects which one is active at runtime.
"""

from __future__ import annotations

import os
from functools import lru_cache
from typing import Type

from dotenv import load_dotenv

load_dotenv(override=False)


# ── Service Metadata ──────────────────────────────────────────────
SERVICE_NAME: str = "grok-ecw-middleware"
SERVICE_VERSION: str = "0.1.0"

# Bind host/port (used by Dockerfile CMD via env vars)
HOST: str = os.getenv("HOST", "0.0.0.0")
PORT: int = int(os.getenv("PORT", "8080"))
LOG_LEVEL: str = os.getenv("LOG_LEVEL", "INFO").upper()


# ── LLM Configuration ────────────────────────────────────────────
XAI_API_KEY: str = os.getenv("XAI_API_KEY", "")
XAI_BASE_URL: str = os.getenv("XAI_BASE_URL", "https://api.x.ai/v1")
LLM_MODEL: str = os.getenv("LLM_MODEL", "grok-3-mini")
LLM_TEMPERATURE: float = float(os.getenv("LLM_TEMPERATURE", "0.1"))
LLM_TIMEOUT_SECONDS: int = int(os.getenv("LLM_TIMEOUT_SECONDS", "60"))

# Absolute path to the extraction prompt file. Resolved relative to this
# file so it works regardless of the caller's current working directory
# (critical for containerized deployments).
EXTRACTION_PROMPT_PATH: str = os.getenv(
    "EXTRACTION_PROMPT_PATH",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "prompts", "extraction.txt"),
)


# ── ECW Configuration ────────────────────────────────────────────
ECW_BASE_URL: str = os.getenv("ECW_BASE_URL", "")
ECW_USERNAME: str = os.getenv("ECW_USERNAME", "")
ECW_PASSWORD: str = os.getenv("ECW_PASSWORD", "")
ECW_CLIENT_ID: str = os.getenv("ECW_CLIENT_ID", "")
ECW_CLIENT_SECRET: str = os.getenv("ECW_CLIENT_SECRET", "")
ECW_PRACTICE_ID: str = os.getenv("ECW_PRACTICE_ID", "")
ECW_REQUEST_TIMEOUT_SECONDS: int = int(os.getenv("ECW_REQUEST_TIMEOUT_SECONDS", "30"))


# ── Output Connector Selection ───────────────────────────────────
OUTPUT_CONNECTOR: str = os.getenv("OUTPUT_CONNECTOR", "generic_server").lower()

GENERIC_SERVER_URL: str = os.getenv("GENERIC_SERVER_URL", "")
GENERIC_SERVER_API_KEY: str = os.getenv("GENERIC_SERVER_API_KEY", "")

GOOGLE_DRIVE_FOLDER_ID: str = os.getenv("GOOGLE_DRIVE_FOLDER_ID", "")
GOOGLE_SERVICE_ACCOUNT_JSON: str = os.getenv("GOOGLE_SERVICE_ACCOUNT_JSON", "")

S3_BUCKET: str = os.getenv("S3_BUCKET", "")
S3_PREFIX: str = os.getenv("S3_PREFIX", "patient-records/")
AWS_REGION: str = os.getenv("AWS_REGION", "us-east-1")


# ── Factory: LLM Client ──────────────────────────────────────────
@lru_cache(maxsize=1)
def get_llm_client():
    """
    Return the active LLM client instance.

    To swap providers (e.g. to OpenAI, Anthropic, local Ollama), write a
    new class that subclasses `llm.base.BaseLLMClient` and change only the
    import + instantiation below. Nothing else in the codebase needs to
    change.
    """
    from llm.grok import GrokLLMClient

    return GrokLLMClient(
        api_key=XAI_API_KEY,
        base_url=XAI_BASE_URL,
        model=LLM_MODEL,
        temperature=LLM_TEMPERATURE,
        timeout_seconds=LLM_TIMEOUT_SECONDS,
        system_prompt_path=EXTRACTION_PROMPT_PATH,
    )


# ── Factory: Output Connector ────────────────────────────────────
def _connector_registry() -> dict[str, Type]:
    """Lazy registry to avoid circular imports at module load time."""
    from connectors.generic_server import GenericServerConnector
    from connectors.google_drive import GoogleDriveConnector
    from connectors.s3 import S3Connector

    return {
        "generic_server": GenericServerConnector,
        "google_drive": GoogleDriveConnector,
        "s3": S3Connector,
    }


@lru_cache(maxsize=1)
def get_output_connector():
    """
    Return the active output connector based on OUTPUT_CONNECTOR env var.

    Raises ValueError if the name is unknown so misconfiguration fails
    loudly at startup rather than silently dropping records.
    """
    registry = _connector_registry()
    if OUTPUT_CONNECTOR not in registry:
        valid = ", ".join(sorted(registry.keys()))
        raise ValueError(
            f"Unknown OUTPUT_CONNECTOR '{OUTPUT_CONNECTOR}'. Valid options: {valid}"
        )
    return registry[OUTPUT_CONNECTOR]()


# ── Factory: ECW Client ──────────────────────────────────────────
@lru_cache(maxsize=1)
def get_ecw_client():
    """Return the active ECW client. Currently a stub implementation."""
    from ecw.client import ECWClient

    return ECWClient(
        base_url=ECW_BASE_URL,
        username=ECW_USERNAME,
        password=ECW_PASSWORD,
        client_id=ECW_CLIENT_ID,
        client_secret=ECW_CLIENT_SECRET,
        practice_id=ECW_PRACTICE_ID,
        timeout_seconds=ECW_REQUEST_TIMEOUT_SECONDS,
    )


# ── Validation ───────────────────────────────────────────────────
def validate_required_env() -> list[str]:
    """
    Return a list of human-readable warnings for missing required config.

    This is NON-FATAL at startup: the service will still boot so that
    /health can respond and the user can debug. Endpoints that actually
    need the missing values will fail at call time with clear errors.
    """
    warnings: list[str] = []

    if not XAI_API_KEY:
        warnings.append("XAI_API_KEY is not set — /fill-patient-record will fail.")

    if OUTPUT_CONNECTOR == "generic_server" and not GENERIC_SERVER_URL:
        warnings.append(
            "OUTPUT_CONNECTOR=generic_server but GENERIC_SERVER_URL is not set."
        )

    if OUTPUT_CONNECTOR == "s3" and not S3_BUCKET:
        warnings.append("OUTPUT_CONNECTOR=s3 but S3_BUCKET is not set.")

    if OUTPUT_CONNECTOR == "google_drive" and not GOOGLE_DRIVE_FOLDER_ID:
        warnings.append(
            "OUTPUT_CONNECTOR=google_drive but GOOGLE_DRIVE_FOLDER_ID is not set."
        )

    return warnings
