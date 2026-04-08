"""
input_adapter.py — Abstract interface for flexible form input sources.

Provides a pluggable adapter pattern so the server can accept transcripts
from various sources (raw text, audio files, PDFs, HL7 messages) without
coupling the processing logic to a specific input format.

To add a new adapter:
  1. Subclass InputAdapter
  2. Implement get_transcript() -> str
  3. Wire it into the /process endpoint in main.py
"""

from __future__ import annotations

from abc import ABC, abstractmethod


class InputAdapter(ABC):
    """
    Abstract base class for transcript input adapters.

    Each adapter is responsible for converting a specific input format
    into a plain-text clinical transcript string that can be processed
    by the LLM.
    """

    @abstractmethod
    def get_transcript(self) -> str:
        """
        Extract and return the clinical transcript as a plain-text string.

        Returns:
            str: The transcript text ready for LLM processing.

        Raises:
            NotImplementedError: If the adapter is a stub.
            ValueError: If the input cannot be parsed.
        """
        ...


class RawTextAdapter(InputAdapter):
    """
    Adapter for plain-text transcript input.

    Simply returns the text as-is. This is the default adapter used
    by the /process endpoint.
    """

    def __init__(self, text: str) -> None:
        self._text = text

    def get_transcript(self) -> str:
        """Return the raw text transcript."""
        return self._text


class AudioFileAdapter(InputAdapter):
    """
    Adapter for audio file input (e.g., recorded dictation).

    Not yet implemented. When implemented, this adapter should:
      - Accept an audio file path or bytes
      - Use a speech-to-text service (e.g., Whisper) to transcribe
      - Return the transcribed text

    Interface:
        AudioFileAdapter(audio_path: str) or AudioFileAdapter(audio_bytes: bytes)
        .get_transcript() -> str
    """

    def __init__(self, audio_path: str) -> None:
        self._audio_path = audio_path

    def get_transcript(self) -> str:
        raise NotImplementedError(
            "AudioFileAdapter is not yet implemented. "
            "When ready, integrate a speech-to-text service (e.g., OpenAI Whisper) "
            "to transcribe the audio file at self._audio_path and return the text."
        )


class PDFAdapter(InputAdapter):
    """
    Adapter for PDF document input (e.g., scanned clinical notes).

    Not yet implemented. When implemented, this adapter should:
      - Accept a PDF file path or bytes
      - Use OCR or PDF text extraction to get the content
      - Return the extracted text

    Interface:
        PDFAdapter(pdf_path: str) or PDFAdapter(pdf_bytes: bytes)
        .get_transcript() -> str
    """

    def __init__(self, pdf_path: str) -> None:
        self._pdf_path = pdf_path

    def get_transcript(self) -> str:
        raise NotImplementedError(
            "PDFAdapter is not yet implemented. "
            "When ready, use a library like PyMuPDF or pytesseract to extract "
            "text from the PDF at self._pdf_path and return it."
        )


class HL7Adapter(InputAdapter):
    """
    Adapter for HL7 message input (e.g., ADT, ORU messages).

    Not yet implemented. When implemented, this adapter should:
      - Accept an HL7 message string or file path
      - Parse the HL7 segments to extract relevant clinical data
      - Return a structured text summary suitable for LLM processing

    Interface:
        HL7Adapter(hl7_message: str)
        .get_transcript() -> str
    """

    def __init__(self, hl7_message: str) -> None:
        self._hl7_message = hl7_message

    def get_transcript(self) -> str:
        raise NotImplementedError(
            "HL7Adapter is not yet implemented. "
            "When ready, use a library like python-hl7 to parse the HL7 message "
            "and extract relevant clinical fields into a readable transcript."
        )
