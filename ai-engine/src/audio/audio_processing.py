import librosa
import numpy as np

SAMPLE_RATE = 48000
SEGMENT_SECONDS = 10


def load_audio(audio_path):
    """
    Decodifica el audio, lo convierte a mono
    y lo remuestrea a 48 kHz.
    """
    audio, _ = librosa.load(
        audio_path,
        sr=SAMPLE_RATE,
        mono=True,
    )

    if len(audio) == 0:
        raise ValueError("Audio vacío")

    audio = np.asarray(audio, dtype=np.float32)

    if not np.isfinite(audio).all():
        raise ValueError("Audio con valores inválidos")

    return audio


def split_audio(audio):
    """
    Devuelve fragmentos completos con sus
    índices y posiciones temporales.
    """
    size = SAMPLE_RATE * SEGMENT_SECONDS

    segments = []

    for start in range(0, len(audio), size):
        segment = audio[start:start + size]

        if len(segment) < size:
            continue

        rms = np.sqrt(
            np.mean(segment.astype(np.float64) ** 2)
        )

        if rms < 0.001:
            continue

        segments.append({
            "index": start // size,
            "start_seconds": start / SAMPLE_RATE,
            "end_seconds": (start + size) / SAMPLE_RATE,
            "audio": segment,
        })

    return segments