import asyncio
import io
import logging
import os
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import numpy as np
import soundfile as sf

from src.api.errors import ApiError
from src.audio.audio_processing import load_audio, split_audio
from src.embeddings.vectors import normalize, to_vector_literal

logger = logging.getLogger("soundmatch")


def create_encoder(settings):
    # CLAP also constructs RoBERTa. Its auxiliary assets must already be cached.
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["TRANSFORMERS_OFFLINE"] = "1"
    from src.embeddings.clap_encoder import CLAPEncoder
    return CLAPEncoder(checkpoint=settings.checkpoint,
                       device=None if settings.device == "auto" else settings.device)


def decode_segments(data: bytes, max_seconds: float):
    try:
        with sf.SoundFile(io.BytesIO(data)) as audio_file:
            if audio_file.format not in {"WAV", "WAVEX", "FLAC", "OGG", "MP3"}:
                raise ApiError(415, "UNSUPPORTED_AUDIO", "Formato de audio no compatible")
            if not 8000 <= audio_file.samplerate <= 192000 or not 1 <= audio_file.channels <= 2:
                raise ApiError(400, "INVALID_AUDIO", "Frecuencia o canales no compatibles")
            duration = audio_file.frames / audio_file.samplerate
            if not 10 <= duration <= max_seconds:
                raise ApiError(400, "INVALID_AUDIO", f"Duración permitida: 10 a {max_seconds:g} segundos")
            # SoundFile validates the actual container; no extension-based decoder fallback.
            # Duration, rate and channel checks bound the decoded allocation before librosa.
            audio = load_audio(audio_file)
    except ApiError:
        raise
    except (sf.LibsndfileError, ValueError, RuntimeError, EOFError):
        raise ApiError(415, "UNSUPPORTED_AUDIO", "Audio inválido o no compatible") from None
    segments = split_audio(audio)
    if not segments:
        raise ApiError(400, "INVALID_AUDIO", "Audio sin fragmentos válidos de diez segundos")
    return segments


class InferenceService:
    """One model and one worker per process; no unbounded inference queue."""
    def __init__(self, settings, encoder_factory=create_encoder):
        self.settings, self.encoder_factory = settings, encoder_factory
        self.encoder = None
        self.state = "loading"
        self.executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="clap")
        self.slot = threading.BoundedSemaphore(1)

    def load(self):
        try:
            self.encoder = self.encoder_factory(self.settings)
            self.state = "ready"
            logger.info("model_ready")
        except Exception as exc:
            self.state = "failed"
            logger.error("model_load_failed", extra={"error_type": type(exc).__name__})

    async def encode(self, data: bytes):
        if self.state != "ready":
            raise ApiError(503, "MODEL_UNAVAILABLE", "Modelo de IA no disponible")
        if not self.slot.acquire(blocking=False):
            raise ApiError(503, "INFERENCE_BUSY", "Motor de IA ocupado")
        future = self.executor.submit(self._encode, data)
        future.add_done_callback(lambda _: self.slot.release())
        wrapped = asyncio.wrap_future(future)
        # Observe late exceptions after a timeout/cancel; retain the slot until work ends.
        wrapped.add_done_callback(lambda f: f.exception() if not f.cancelled() else None)
        try:
            return await asyncio.wait_for(asyncio.shield(wrapped), self.settings.inference_timeout_seconds)
        except TimeoutError:
            raise ApiError(504, "INFERENCE_TIMEOUT", "Tiempo de inferencia agotado") from None

    def _encode(self, data: bytes):
        started = time.perf_counter()
        try:
            segments = decode_segments(data, self.settings.max_audio_seconds)
            vectors = [self.encoder.encode(segment["audio"]) for segment in segments]
            for vector in vectors:
                to_vector_literal(vector)
            embedding = normalize(np.mean(vectors, axis=0)).astype(np.float32)
            to_vector_literal(embedding)
            return embedding
        finally:
            logger.info("inference", extra={"duration_ms": round((time.perf_counter()-started)*1000, 2)})

    def close(self):
        self.executor.shutdown(wait=True, cancel_futures=True)
        self.encoder = None
