"""
connectors/generic_server.py — POST payloads to an arbitrary HTTP URL.

Placeholder implementation. When filled in, this connector will:
  - POST the `data` dict as JSON to `GENERIC_SERVER_URL`.
  - Include `Authorization: Bearer {GENERIC_SERVER_API_KEY}` if set.
  - Honor a short timeout and raise ConnectorError on non-2xx responses.
"""

from __future__ import annotations

import logging

from connectors.base import BaseOutputConnector, ConnectorError

logger = logging.getLogger(__name__)


class GenericServerConnector(BaseOutputConnector):
    """POSTs patient-record payloads to a configurable webhook URL."""

    async def send(self, data: dict) -> None:
        """
        Forward `data` to the configured generic webhook.

        Eventual implementation:
          import httpx, config
          async with httpx.AsyncClient(timeout=15) as client:
              headers = {"Content-Type": "application/json"}
              if config.GENERIC_SERVER_API_KEY:
                  headers["Authorization"] = f"Bearer {config.GENERIC_SERVER_API_KEY}"
              resp = await client.post(config.GENERIC_SERVER_URL, json=data, headers=headers)
              if resp.status_code >= 400:
                  raise ConnectorError(f"Generic server returned {resp.status_code}: {resp.text}")
        """
        logger.info(
            "GenericServerConnector.send() called with %d-field payload — "
            "NOT YET IMPLEMENTED.",
            len(data),
        )
        raise NotImplementedError(
            "GenericServerConnector.send() is a stub. "
            "Implement an httpx POST to config.GENERIC_SERVER_URL with the "
            "payload as JSON and appropriate auth headers."
        )
