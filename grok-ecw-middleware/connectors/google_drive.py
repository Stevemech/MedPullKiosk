"""
connectors/google_drive.py — upload payloads to a Google Drive folder.

Placeholder implementation. When filled in, this connector will:
  - Authenticate via a service account (GOOGLE_SERVICE_ACCOUNT_JSON).
  - Create one JSON file per call in `GOOGLE_DRIVE_FOLDER_ID`, named
    `{timestamp}_{patient_id_or_uuid}.json`.
"""

from __future__ import annotations

import logging

from connectors.base import BaseOutputConnector, ConnectorError

logger = logging.getLogger(__name__)


class GoogleDriveConnector(BaseOutputConnector):
    """Writes patient-record payloads as JSON files to Google Drive."""

    async def send(self, data: dict) -> None:
        """
        Upload `data` as a JSON file to the configured Drive folder.

        Eventual implementation:
          - Parse config.GOOGLE_SERVICE_ACCOUNT_JSON (either a JSON
            string literal or a path to a credentials file).
          - Build a `google.oauth2.service_account.Credentials` instance
            with `https://www.googleapis.com/auth/drive.file` scope.
          - Use `googleapiclient.discovery.build('drive', 'v3', ...)`
            to create a file in `config.GOOGLE_DRIVE_FOLDER_ID` with
            mimeType='application/json' and the payload as contents.
          - Run the blocking google-api-python-client call in a thread
            (`asyncio.to_thread`) to keep the FastAPI event loop responsive.
        """
        logger.info(
            "GoogleDriveConnector.send() called with %d-field payload — "
            "NOT YET IMPLEMENTED.",
            len(data),
        )
        raise NotImplementedError(
            "GoogleDriveConnector.send() is a stub. "
            "Implement service-account auth and a Drive v3 file.create "
            "call into config.GOOGLE_DRIVE_FOLDER_ID."
        )
