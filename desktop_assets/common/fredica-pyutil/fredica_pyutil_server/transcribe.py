# -*- coding: UTF-8 -*-
"""
faster-whisper transcription helper.

Loaded lazily on first request to avoid heavy import time at server startup.
"""
from __future__ import annotations

_model_cache: dict = {}


def _get_model(model_name: str, device: str = "auto", compute_type: str = "float16"):
    key = (model_name, device, compute_type)
    if key not in _model_cache:
        from faster_whisper import WhisperModel
        _model_cache[key] = WhisperModel(model_name, device=device, compute_type=compute_type)
    return _model_cache[key]


async def transcribe_chunk(
    audio_path: str,
    model_name: str = "large-v3",
    language: str | None = None,
    device: str = "auto",
    compute_type: str = "float16",
) -> dict:
    """
    Transcribe a single audio chunk with faster-whisper.

    Returns:
        {
            "segments": [{"start": float, "end": float, "text": str}, ...],
            "text": str,
            "language": str,
        }
    """
    model = _get_model(model_name, device, compute_type)
    segments_iter, info = model.transcribe(audio_path, language=language)
    seg_list = [
        {"start": s.start, "end": s.end, "text": s.text}
        for s in segments_iter
    ]
    full_text = " ".join(s["text"].strip() for s in seg_list)
    return {
        "segments": seg_list,
        "text": full_text,
        "language": info.language,
    }
