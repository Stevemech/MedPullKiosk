"""
llm/grok.py — Grok (xAI) implementation of BaseLLMClient.

Uses the official `openai` Python SDK pointed at xAI's OpenAI-compatible
endpoint (`https://api.x.ai/v1`). This makes swapping to vanilla OpenAI
a base_url change, and swapping to Anthropic / Ollama / etc. a matter
of writing a new subclass alongside this one.
"""

from __future__ import annotations

import json
import logging
import os
from typing import Any

from openai import AsyncOpenAI
from openai import APIError, APIConnectionError, APITimeoutError

from llm.base import BaseLLMClient, LLMExtractionError

logger = logging.getLogger(__name__)


class GrokLLMClient(BaseLLMClient):
    """
    Grok LLM client.

    Thin wrapper around `openai.AsyncOpenAI` configured for xAI. The class
    is intentionally small — most of the intelligence lives in the system
    prompt at `prompts/extraction.txt`.
    """

    def __init__(
        self,
        api_key: str,
        base_url: str,
        model: str,
        temperature: float,
        timeout_seconds: int,
        system_prompt_path: str,
    ) -> None:
        self._model = model
        self._temperature = temperature
        self._system_prompt = self._load_system_prompt(system_prompt_path)

        self._client = AsyncOpenAI(
            api_key=api_key or "missing-xai-api-key",
            base_url=base_url,
            timeout=timeout_seconds,
        )

    @staticmethod
    def _load_system_prompt(path: str) -> str:
        """Read the extraction prompt from disk once at construction time."""
        if not os.path.exists(path):
            raise FileNotFoundError(
                f"Extraction prompt not found at '{path}'. "
                f"Check EXTRACTION_PROMPT_PATH or prompts/extraction.txt."
            )
        with open(path, "r", encoding="utf-8") as f:
            return f.read().strip()

    async def extract(self, raw_input: str) -> dict:
        """
        Call Grok with the extraction prompt and return parsed JSON.

        The model is pinned to `response_format={"type": "json_object"}`
        which instructs xAI to emit well-formed JSON. Schema validation
        is done one layer up (in main.py) against the Pydantic models.
        """
        if not raw_input or not raw_input.strip():
            raise LLMExtractionError("Empty input provided to LLM.")

        messages: list[dict[str, Any]] = [
            {"role": "system", "content": self._system_prompt},
            {"role": "user", "content": raw_input},
        ]

        logger.info(
            "Calling Grok model=%s temperature=%.2f input_len=%d",
            self._model, self._temperature, len(raw_input),
        )

        try:
            response = await self._client.chat.completions.create(
                model=self._model,
                messages=messages,
                temperature=self._temperature,
                response_format={"type": "json_object"},
            )
        except APITimeoutError as e:
            raise LLMExtractionError(f"Grok request timed out: {e}") from e
        except APIConnectionError as e:
            raise LLMExtractionError(f"Cannot connect to Grok: {e}") from e
        except APIError as e:
            raise LLMExtractionError(f"Grok API error: {e}") from e

        if not response.choices:
            raise LLMExtractionError("Grok returned no choices.")

        content = response.choices[0].message.content
        if not content:
            raise LLMExtractionError("Grok returned an empty message.")

        try:
            parsed = json.loads(content)
        except json.JSONDecodeError as e:
            logger.error("Grok response was not valid JSON: %s", content[:500])
            raise LLMExtractionError(f"Grok returned invalid JSON: {e}") from e

        if not isinstance(parsed, dict):
            raise LLMExtractionError(
                f"Grok returned a non-object JSON value: {type(parsed).__name__}"
            )

        logger.info("Grok extraction complete — %d top-level fields", len(parsed))
        return parsed
