import asyncio
import threading
from unittest.mock import Mock, patch

import numpy as np
import pytest

from src.api.errors import ApiError
from src.api.settings import Settings
from src.services.inference import InferenceService


def test_timeout_keeps_slot_until_worker_finishes():
    entered, release = threading.Event(), threading.Event()
    service = InferenceService(Settings(inference_timeout_seconds=.02), lambda _: Mock())
    service.load()

    def slow(data):
        entered.set()
        release.wait(5)
        return np.ones(512)

    async def scenario():
        with patch.object(service, "_encode", slow):
            try:
                with pytest.raises(ApiError) as timeout:
                    await service.encode(b"audio")
                assert entered.is_set()
                assert timeout.value.status == 504
                with pytest.raises(ApiError) as busy:
                    await service.encode(b"audio")
                assert busy.value.code == "INFERENCE_BUSY"
            finally:
                release.set()
                await asyncio.to_thread(service.close)
    asyncio.run(scenario())


def test_model_load_failure_is_explicit():
    service = InferenceService(Settings(), Mock(side_effect=RuntimeError("missing weights")))
    try:
        service.load()
        assert service.state == "failed"
        with pytest.raises(ApiError) as error:
            asyncio.run(service.encode(b"audio"))
        assert error.value.status == 503
    finally:
        service.close()


def test_invalid_embedding_dimensions():
    encoder = Mock()
    encoder.encode.return_value = np.ones(511)
    service = InferenceService(Settings(), lambda _: encoder)
    service.load()
    try:
        with patch("src.services.inference.decode_segments", return_value=[{"audio": np.ones(480000)}]):
            with pytest.raises(ValueError):
                asyncio.run(service.encode(b"audio"))
    finally:
        service.close()
