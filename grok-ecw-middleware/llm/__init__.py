"""LLM abstraction layer. See `base.py` for the hot-swap contract."""

from llm.base import BaseLLMClient, LLMExtractionError

__all__ = ["BaseLLMClient", "LLMExtractionError"]
