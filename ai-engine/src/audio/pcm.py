"""Bounded PCM float32 little-endian validation; never decode a compressed container."""
import numpy as np

from src.api.errors import ApiError
from src.audio.audio_processing import prepare_pcm_samples


def decode_pcm(data: bytes, sample_rate: int, channels: int, settings):
    if sample_rate not in settings.pcm_sample_rates or channels != 1:
        raise ApiError(400, "INVALID_PARAMETERS", "Frecuencia o canales PCM no compatibles")
    if len(data) > settings.max_pcm_bytes:
        raise ApiError(413, "FILE_TOO_LARGE", "Ventana PCM demasiado grande")
    expected = sample_rate * settings.pcm_window_seconds * 4
    if not data or len(data) % 4 or len(data) != expected:
        raise ApiError(400, "INVALID_AUDIO", "Se requiere una ventana PCM completa de diez segundos")
    audio = np.frombuffer(data, dtype="<f4").copy()
    if not np.isfinite(audio).all() or np.max(np.abs(audio)) > 1:
        raise ApiError(400, "INVALID_AUDIO", "Muestras PCM no finitas o fuera de [-1, 1]")
    segments = prepare_pcm_samples(audio, sample_rate)
    if len(segments) != 1:
        raise ApiError(400, "INVALID_AUDIO", "Ventana silenciosa o sin muestras validas")
    return segments[0]["audio"]
