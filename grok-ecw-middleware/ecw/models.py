"""
ecw/models.py — Pydantic models for eClinicalWorks patient-record data.

These models are the *contract* that the LLM extraction layer targets.
The fields are chosen based on the MedPull kiosk + Chrome extension
context — the kinds of information a clinic collects at intake that
normally ends up in eCW demographics, problem list, allergies,
medications, vitals, history, and appointment screens.

Design rules:
  - EVERY clinical field is `Optional` and defaults to `None`. The
    extraction prompt instructs the model to emit `null` when it cannot
    confidently determine a value, and we never want required-field
    validation to cause the model's "I don't know" to be discarded.
  - List-valued sections default to an empty list rather than `None` so
    downstream code can iterate without a null check.
  - No PHI-specific defaults or examples are embedded in the models.
"""

from __future__ import annotations

from datetime import date
from enum import Enum
from typing import Any, Optional

from pydantic import BaseModel, ConfigDict, Field


# ══════════════════════════════════════════════════════════════════
# Enums — tight vocabularies that eCW also enforces on its side
# ══════════════════════════════════════════════════════════════════

class Sex(str, Enum):
    MALE = "male"
    FEMALE = "female"
    OTHER = "other"
    UNKNOWN = "unknown"


class MaritalStatus(str, Enum):
    SINGLE = "single"
    MARRIED = "married"
    DIVORCED = "divorced"
    WIDOWED = "widowed"
    SEPARATED = "separated"
    PARTNERED = "partnered"
    UNKNOWN = "unknown"


class AllergySeverity(str, Enum):
    MILD = "mild"
    MODERATE = "moderate"
    SEVERE = "severe"
    LIFE_THREATENING = "life_threatening"
    UNKNOWN = "unknown"


class AllergyType(str, Enum):
    DRUG = "drug"
    FOOD = "food"
    ENVIRONMENTAL = "environmental"
    OTHER = "other"


class SmokingStatus(str, Enum):
    NEVER = "never"
    FORMER = "former"
    CURRENT_EVERY_DAY = "current_every_day"
    CURRENT_SOME_DAYS = "current_some_days"
    UNKNOWN = "unknown"


class ProblemStatus(str, Enum):
    ACTIVE = "active"
    RESOLVED = "resolved"
    INACTIVE = "inactive"
    UNKNOWN = "unknown"


class AppointmentType(str, Enum):
    NEW_PATIENT = "new_patient"
    FOLLOW_UP = "follow_up"
    ANNUAL_PHYSICAL = "annual_physical"
    SICK_VISIT = "sick_visit"
    TELEHEALTH = "telehealth"
    PROCEDURE = "procedure"
    OTHER = "other"


# ══════════════════════════════════════════════════════════════════
# Supporting shapes
# ══════════════════════════════════════════════════════════════════

class Address(BaseModel):
    """Postal address. All fields optional for partial extraction."""
    street_line_1: Optional[str] = None
    street_line_2: Optional[str] = None
    city: Optional[str] = None
    state: Optional[str] = Field(
        default=None,
        description="Two-letter US state code or full state name.",
    )
    postal_code: Optional[str] = None
    country: Optional[str] = Field(default=None, description="ISO country name or code.")


class EmergencyContact(BaseModel):
    name: Optional[str] = None
    relationship: Optional[str] = Field(
        default=None,
        description="e.g. 'spouse', 'parent', 'sibling', 'friend'.",
    )
    phone: Optional[str] = None
    email: Optional[str] = None


# ══════════════════════════════════════════════════════════════════
# Demographics — "Patient Info" / "Demographics" tab in eCW
# ══════════════════════════════════════════════════════════════════

class Demographics(BaseModel):
    """
    Core patient demographics. Maps to eCW's Patient Information screen.

    `patient_id` / `mrn` are typically assigned by eCW. When creating a
    new patient, leave them null; when updating an existing one, include
    whichever identifier you have.
    """

    patient_id: Optional[str] = Field(default=None, description="eCW internal patient id.")
    mrn: Optional[str] = Field(default=None, description="Medical record number.")

    first_name: Optional[str] = None
    middle_name: Optional[str] = None
    last_name: Optional[str] = None
    preferred_name: Optional[str] = None

    date_of_birth: Optional[date] = Field(
        default=None,
        description="ISO-8601 date (YYYY-MM-DD).",
    )
    sex: Optional[Sex] = None
    gender_identity: Optional[str] = None
    pronouns: Optional[str] = None

    ssn: Optional[str] = Field(default=None, description="Last-4 or full SSN, no formatting.")

    primary_phone: Optional[str] = None
    secondary_phone: Optional[str] = None
    email: Optional[str] = None
    address: Optional[Address] = None

    preferred_language: Optional[str] = None
    race: Optional[str] = None
    ethnicity: Optional[str] = None
    marital_status: Optional[MaritalStatus] = None
    occupation: Optional[str] = None
    employer: Optional[str] = None

    emergency_contact: Optional[EmergencyContact] = None


# ══════════════════════════════════════════════════════════════════
# Insurance
# ══════════════════════════════════════════════════════════════════

class InsuranceInfo(BaseModel):
    """Single insurance policy. Patients can have multiple (primary/secondary)."""

    rank: Optional[str] = Field(
        default=None,
        description="'primary', 'secondary', or 'tertiary'.",
    )
    payer_name: Optional[str] = None
    plan_name: Optional[str] = None
    policy_number: Optional[str] = None
    group_number: Optional[str] = None

    subscriber_name: Optional[str] = None
    subscriber_dob: Optional[date] = None
    subscriber_relationship: Optional[str] = Field(
        default=None,
        description="e.g. 'self', 'spouse', 'child'.",
    )

    effective_date: Optional[date] = None
    termination_date: Optional[date] = None


# ══════════════════════════════════════════════════════════════════
# Clinical sections
# ══════════════════════════════════════════════════════════════════

class AllergyItem(BaseModel):
    """Existing allergy on file (read from eCW)."""
    allergen: Optional[str] = None
    allergy_type: Optional[AllergyType] = None
    severity: Optional[AllergySeverity] = None
    reaction: Optional[str] = Field(
        default=None,
        description="Free-text description of the reaction (e.g. 'hives', 'anaphylaxis').",
    )
    onset_date: Optional[date] = None
    notes: Optional[str] = None


class AllergyItemToAdd(BaseModel):
    """
    New allergy being added to the patient's record.

    Mirrors `AllergyItem` but is a distinct type because the "add"
    request carries a `source` attribution that eCW audit logs expect.
    """
    allergen: str = Field(..., description="Required when adding. The substance itself.")
    allergy_type: Optional[AllergyType] = None
    severity: Optional[AllergySeverity] = None
    reaction: Optional[str] = None
    onset_date: Optional[date] = None
    source: Optional[str] = Field(
        default="patient_reported",
        description="Who reported the allergy (e.g. 'patient_reported', 'clinician', 'transcribed').",
    )
    notes: Optional[str] = None


class MedicationItem(BaseModel):
    """One medication on the active med list."""
    name: Optional[str] = None
    rxnorm_code: Optional[str] = None
    dosage: Optional[str] = Field(
        default=None,
        description="e.g. '10 mg', '500 mg/5 mL'.",
    )
    frequency: Optional[str] = Field(
        default=None,
        description="e.g. 'once daily', 'BID', 'PRN'.",
    )
    route: Optional[str] = Field(
        default=None,
        description="e.g. 'oral', 'IV', 'topical'.",
    )
    start_date: Optional[date] = None
    end_date: Optional[date] = None
    prescribing_provider: Optional[str] = None
    indication: Optional[str] = Field(
        default=None,
        description="What the med is being taken for.",
    )
    is_active: Optional[bool] = None


class ProblemListItem(BaseModel):
    """One entry on the patient's problem list / medical history."""
    condition: Optional[str] = None
    icd10_code: Optional[str] = None
    onset_date: Optional[date] = None
    resolved_date: Optional[date] = None
    status: Optional[ProblemStatus] = None
    notes: Optional[str] = None


class FamilyHistoryItem(BaseModel):
    relation: Optional[str] = Field(
        default=None,
        description="e.g. 'mother', 'father', 'paternal grandmother', 'sibling'.",
    )
    condition: Optional[str] = None
    age_at_diagnosis: Optional[int] = None
    is_deceased: Optional[bool] = None
    cause_of_death: Optional[str] = None
    notes: Optional[str] = None


class SocialHistory(BaseModel):
    smoking_status: Optional[SmokingStatus] = None
    packs_per_day: Optional[float] = None
    years_smoking: Optional[float] = None

    alcohol_use: Optional[str] = Field(
        default=None,
        description="Free-text description (e.g. '2 drinks/week', 'none', 'heavy daily use').",
    )
    drug_use: Optional[str] = None
    caffeine_use: Optional[str] = None

    exercise_frequency: Optional[str] = None
    diet_notes: Optional[str] = None

    sexually_active: Optional[bool] = None
    sexual_partners: Optional[str] = None
    contraception: Optional[str] = None

    living_situation: Optional[str] = None
    notes: Optional[str] = None


class Vitals(BaseModel):
    """Vital signs captured at intake or during the visit."""
    measured_at: Optional[str] = Field(
        default=None,
        description="ISO-8601 datetime string, or null if unknown.",
    )
    bp_systolic: Optional[int] = Field(default=None, description="mmHg")
    bp_diastolic: Optional[int] = Field(default=None, description="mmHg")
    pulse_bpm: Optional[int] = None
    respiratory_rate: Optional[int] = None
    temperature_f: Optional[float] = Field(default=None, description="Fahrenheit")
    spo2_percent: Optional[float] = None
    weight_lbs: Optional[float] = None
    height_inches: Optional[float] = None
    bmi: Optional[float] = None
    pain_scale: Optional[int] = Field(default=None, ge=0, le=10)
    head_circumference_cm: Optional[float] = None


class ChiefComplaint(BaseModel):
    """The 'CC' — why the patient is here today."""
    complaint: Optional[str] = None
    duration: Optional[str] = Field(
        default=None,
        description="e.g. '3 days', '2 weeks', 'since Monday'.",
    )
    severity: Optional[str] = Field(
        default=None,
        description="Free text or 0-10 pain scale description.",
    )


class HistoryOfPresentIllness(BaseModel):
    """HPI narrative."""
    narrative: Optional[str] = None
    onset: Optional[str] = None
    location: Optional[str] = None
    duration: Optional[str] = None
    character: Optional[str] = None
    aggravating_factors: Optional[str] = None
    relieving_factors: Optional[str] = None
    timing: Optional[str] = None
    severity: Optional[str] = None
    associated_symptoms: Optional[str] = None


class ReviewOfSystems(BaseModel):
    """
    ROS — each field holds free-text findings for that system, or null
    if the system was not reviewed or the patient denied all symptoms.
    """
    constitutional: Optional[str] = None
    eyes: Optional[str] = None
    ent: Optional[str] = None
    cardiovascular: Optional[str] = None
    respiratory: Optional[str] = None
    gastrointestinal: Optional[str] = None
    genitourinary: Optional[str] = None
    musculoskeletal: Optional[str] = None
    integumentary: Optional[str] = None
    neurological: Optional[str] = None
    psychiatric: Optional[str] = None
    endocrine: Optional[str] = None
    hematologic: Optional[str] = None
    allergic_immunologic: Optional[str] = None


# ══════════════════════════════════════════════════════════════════
# Appointment
# ══════════════════════════════════════════════════════════════════

class AppointmentRequest(BaseModel):
    """Request to schedule or update an appointment in eCW."""
    patient_id: Optional[str] = None

    appointment_date: Optional[date] = None
    appointment_time: Optional[str] = Field(
        default=None,
        description="HH:MM (24h) local clinic time.",
    )
    duration_minutes: Optional[int] = None

    provider_id: Optional[str] = None
    provider_name: Optional[str] = None

    appointment_type: Optional[AppointmentType] = None
    reason: Optional[str] = None

    location_id: Optional[str] = None
    location_name: Optional[str] = None

    notes: Optional[str] = None


# ══════════════════════════════════════════════════════════════════
# Med-hx / Allergy bulk-update request
# ══════════════════════════════════════════════════════════════════

class UpdateMedHxAllergyRequest(BaseModel):
    """
    Bulk-update request for the medical history + allergies sections of
    a patient chart. Distinct from piecewise updates because eCW treats
    these as a single reconciliation transaction.
    """
    patient_id: str = Field(..., description="Target patient. Required.")
    allergies_to_add: list[AllergyItemToAdd] = Field(default_factory=list)
    allergies_to_remove: list[str] = Field(
        default_factory=list,
        description="Allergen names to remove from the current list.",
    )
    medications_to_add: list[MedicationItem] = Field(default_factory=list)
    medications_to_discontinue: list[str] = Field(
        default_factory=list,
        description="Medication names to mark inactive.",
    )
    problems_to_add: list[ProblemListItem] = Field(default_factory=list)
    family_history_to_add: list[FamilyHistoryItem] = Field(default_factory=list)
    reviewed_by: Optional[str] = Field(
        default=None,
        description="Clinician user id / name who reconciled these updates.",
    )


# ══════════════════════════════════════════════════════════════════
# Top-level PatientRecord (what the LLM is asked to produce)
# ══════════════════════════════════════════════════════════════════

class PatientRecord(BaseModel):
    """
    Unified patient record produced by the LLM and consumed by the ECW
    client. This is the canonical contract for the extraction prompt —
    every top-level key here maps 1:1 to an eCW subsystem.
    """
    model_config = ConfigDict(extra="ignore")

    demographics: Optional[Demographics] = None
    insurance: list[InsuranceInfo] = Field(default_factory=list)

    chief_complaint: Optional[ChiefComplaint] = None
    history_of_present_illness: Optional[HistoryOfPresentIllness] = None
    review_of_systems: Optional[ReviewOfSystems] = None

    vitals: Optional[Vitals] = None

    allergies: list[AllergyItemToAdd] = Field(default_factory=list)
    medications: list[MedicationItem] = Field(default_factory=list)
    problems: list[ProblemListItem] = Field(default_factory=list)
    family_history: list[FamilyHistoryItem] = Field(default_factory=list)
    social_history: Optional[SocialHistory] = None

    appointment: Optional[AppointmentRequest] = None

    free_text_notes: Optional[str] = Field(
        default=None,
        description=(
            "Catch-all for content the LLM extracted but could not slot "
            "into a structured field. Useful for manual review downstream."
        ),
    )


# ══════════════════════════════════════════════════════════════════
# Input payload for POST /fill-patient-record
# ══════════════════════════════════════════════════════════════════

class PatientIntakePayload(BaseModel):
    """
    Wire format accepted by POST /fill-patient-record.

    One of `raw_text` or `structured` MUST be present. If `raw_text` is
    provided it goes to the LLM; if `structured` is provided it skips
    the LLM and goes straight to the ECW client.
    """
    raw_text: Optional[str] = Field(
        default=None,
        description="Free-text patient intake (kiosk transcript, dictation, etc).",
    )
    structured: Optional[dict[str, Any]] = Field(
        default=None,
        description=(
            "Pre-structured payload. Skips the LLM extraction step; the "
            "server will validate directly against PatientRecord."
        ),
    )
    patient_id: Optional[str] = Field(
        default=None,
        description=(
            "Optional eCW patient id to attach to the record. When "
            "present, the middleware treats this as an update; when "
            "absent, it treats as a new patient create."
        ),
    )
    source: Optional[str] = Field(
        default="api",
        description="Provenance tag ('kiosk', 'extension', 'api', etc).",
    )
