"""
connectors/base.py — abstract interface for output connectors.

An output connector is a side-channel that receives a copy of every
fill-patient-record payload after the ECW write succeeds. Common uses:
  - Archive records to S3 / GCS / Google Drive for audit + retraining.
  - POST to a generic downstream webhook for real-time analytics.
  - Write to a message queue (Kafka, SQS) for async consumers.

Hot-swap contract: implementations subclass `BaseOutputConnector` and
register themselves in `config._connector_registry()`. The active
connector is chosen at runtime by the `OUTPUT_CONNECTOR` env var.
"""

from __future__ import annotations

from abc import ABC, abstractmethod


class ConnectorError(Exception):
    """Raised when a connector cannot persist the given payload."""


class BaseOutputConnector(ABC):
    """
    Abstract output connector.

    Implementations must be safe to instantiate at import time (no
    network IO in __init__) and must NOT raise on construction if the
    credentials are missing — they should raise at `send()` time instead,
    so the service can boot with a misconfigured connector for local
    development and still respond to /health.
    """

    @abstractmethod
    async def send(self, data: dict) -> None:
        """
        Persist or forward `data` to the connector's backing destination.

        Args:
            data: A JSON-serializable dict representing the full fill
                  result (patient record + ecw_response + metadata).

        Raises:
            ConnectorError: If the send fails. The caller is responsible
                            for deciding whether to surface the error or
                            log-and-continue (currently it logs-and-continues
                            so a broken connector does not block the ECW write).
        """
