"""
llm/base.py — abstract interface for swappable LLM backends.

Every LLM integration (Grok, OpenAI, Anthropic, a local Ollama server,
etc.) must subclass `BaseLLMClient` and implement `extract()`. Swapping
providers requires:

  1. Writing a new subclass in `llm/<provider>.py`.
  2. Returning an instance of that subclass from `config.get_llm_client()`.

No other code in the service should import a concrete provider directly.
"""

from __future__ import annotations

from abc import ABC, abstractmethod


class LLMExtractionError(Exception):
    """Raised when the LLM cannot produce valid structured output."""


class BaseLLMClient(ABC):
    """
    Abstract contract for any LLM that can turn free-text patient intake
    into a structured dict matching the ECW Pydantic models.

    Implementations are responsible for:
      - Loading and applying the system prompt.
      - Requesting JSON-formatted output from the underlying model.
      - Parsing the response into a Python dict (NOT a Pydantic model —
        validation happens one layer up so the LLM layer stays decoupled
        from ECW's schema).
      - Raising `LLMExtractionError` on parse failure so callers can
        distinguish between model errors and unrelated exceptions.
    """

    @abstractmethod
    async def extract(self, raw_input: str) -> dict:
        """
        Extract structured patient-record fields from free-text or JSON input.

        Args:
            raw_input: A patient intake payload. May be a plain-text
                       transcript (e.g. kiosk dictation), a JSON-encoded
                       string, or any other text the model should read.

        Returns:
            A dict shaped like the top-level `PatientRecord` Pydantic model.
            Fields the model cannot confidently determine must be `null`.

        Raises:
            LLMExtractionError: If the response cannot be parsed as JSON
                                or is otherwise unusable.
        """
        ...
