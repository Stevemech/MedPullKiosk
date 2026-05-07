"""
ecw/base.py — abstract interface for the ECW write layer.

`ECWClient` (in `client.py`) subclasses `BaseECWClient`. Swapping to a
different EHR (Epic, Athena, NextGen, etc.) would mean writing another
subclass and returning it from `config.get_ecw_client()`.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Optional

from ecw.models import (
    AppointmentRequest,
    Demographics,
    InsuranceInfo,
    PatientRecord,
    UpdateMedHxAllergyRequest,
    Vitals,
    ChiefComplaint,
    HistoryOfPresentIllness,
    ReviewOfSystems,
    SocialHistory,
)


class ECWAuthError(Exception):
    """Raised on 401/403 responses after a refresh attempt has been made."""


class ECWAPIError(Exception):
    """Raised on non-auth ECW API failures (5xx, malformed responses, etc)."""


class BaseECWClient(ABC):
    """
    Abstract eCW client contract.

    Every method is async because real ECW calls will hit HTTP endpoints.
    Authentication is the implementation's responsibility — callers never
    pass tokens directly.
    """

    # ── Auth ───────────────────────────────────────────────────────

    @abstractmethod
    async def authenticate(self) -> None:
        """Acquire an access token. Called lazily on first request."""

    @abstractmethod
    async def refresh_tokens(self) -> None:
        """Refresh the access token using the stored refresh_token / credentials."""

    # ── Patient write operations ──────────────────────────────────

    @abstractmethod
    async def create_patient(self, demographics: Demographics) -> str:
        """Create a new patient and return the assigned patient_id."""

    @abstractmethod
    async def update_demographics(
        self, patient_id: str, demographics: Demographics
    ) -> None:
        """Overwrite demographics on an existing patient."""

    @abstractmethod
    async def update_insurance(
        self, patient_id: str, insurance_policies: list[InsuranceInfo]
    ) -> None:
        """Replace the full insurance policy set for a patient."""

    @abstractmethod
    async def update_med_hx_allergy(
        self, request: UpdateMedHxAllergyRequest
    ) -> None:
        """Reconcile allergies, medications, problems, and family history."""

    @abstractmethod
    async def submit_vitals(self, patient_id: str, vitals: Vitals) -> None:
        """Record a set of vitals for the current encounter."""

    @abstractmethod
    async def submit_chief_complaint(
        self, patient_id: str, complaint: ChiefComplaint
    ) -> None:
        """Set the chief complaint on the current encounter."""

    @abstractmethod
    async def submit_hpi(
        self, patient_id: str, hpi: HistoryOfPresentIllness
    ) -> None:
        """Set the history-of-present-illness narrative."""

    @abstractmethod
    async def submit_ros(
        self, patient_id: str, ros: ReviewOfSystems
    ) -> None:
        """Set the review-of-systems entries."""

    @abstractmethod
    async def submit_social_history(
        self, patient_id: str, social: SocialHistory
    ) -> None:
        """Set the social history section."""

    @abstractmethod
    async def create_appointment(
        self, request: AppointmentRequest
    ) -> str:
        """Create an appointment and return the eCW appointment_id."""

    # ── Orchestration ─────────────────────────────────────────────

    @abstractmethod
    async def write_patient_record(
        self,
        record: PatientRecord,
        patient_id: Optional[str] = None,
    ) -> dict:
        """
        Persist an entire `PatientRecord` to eCW.

        Should short-circuit gracefully when a section of the record is
        null/empty (i.e. the LLM had nothing to contribute). Returns a
        dict summarizing what was written — patient_id, appointment_id,
        and per-section status flags.
        """
