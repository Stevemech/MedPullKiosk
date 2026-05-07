"""
connectors/s3.py — upload payloads to an S3 bucket.

Placeholder implementation. When filled in, this connector will:
  - Write one JSON object per call to `s3://{S3_BUCKET}/{S3_PREFIX}...`.
  - Use aioboto3 so the upload is async-native.
  - Name objects `{iso_timestamp}_{patient_id_or_uuid}.json`.
"""

from __future__ import annotations

import logging

from connectors.base import BaseOutputConnector, ConnectorError

logger = logging.getLogger(__name__)


class S3Connector(BaseOutputConnector):
    """Writes patient-record payloads as JSON objects to S3."""

    async def send(self, data: dict) -> None:
        """
        Upload `data` as a JSON object to the configured S3 bucket.

        Eventual implementation:
          import aioboto3, json, datetime, uuid, config
          session = aioboto3.Session()
          async with session.client("s3", region_name=config.AWS_REGION) as s3:
              key = (
                  f"{config.S3_PREFIX}"
                  f"{datetime.datetime.utcnow().isoformat()}"
                  f"_{data.get('patient_id') or uuid.uuid4()}.json"
              )
              await s3.put_object(
                  Bucket=config.S3_BUCKET,
                  Key=key,
                  Body=json.dumps(data).encode(),
                  ContentType="application/json",
                  ServerSideEncryption="AES256",
              )
        """
        logger.info(
            "S3Connector.send() called with %d-field payload — "
            "NOT YET IMPLEMENTED.",
            len(data),
        )
        raise NotImplementedError(
            "S3Connector.send() is a stub. "
            "Implement aioboto3 put_object into config.S3_BUCKET under "
            "config.S3_PREFIX with server-side encryption enabled."
        )
